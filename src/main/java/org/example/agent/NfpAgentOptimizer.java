package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.example.beamsearch.application.NFPToBeamSearchBridge;
import org.example.nfp.BatchBlockStitcher;
import org.example.nfp.Block;
import org.example.nfp.NfpSearchSession;
import org.example.nfp.PolygonItem;
import org.example.nfp.selection.NfpScoringProfile;
import org.example.nfp.selection.NfpSelectionTrace;
import org.example.nfp.selection.ProfileDrivenNfpSelectionModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.example.agent.NfpExperimentEvaluator.*;

/** Agent 专属的有界优化器；不改变 {@code BaselinePackingApplication} 的基准路径。 */
final class NfpAgentOptimizer {
    record Settings(int rounds, int quickCandidates, long quickMs, long mediumMs, long finalMs,
                    long wallMs, long nfpMs, boolean useLlm) {
        Settings {
            if (rounds < 1 || quickCandidates < 1 || quickMs < 1 || mediumMs < quickMs
                    || finalMs < mediumMs || wallMs < finalMs || nfpMs < 1) throw new IllegalArgumentException("实验预算非法");
        }
    }
    private record Trial(Path directory, Path plan, JsonObject result, Measurement quick) { }
    private final Settings settings;

    NfpAgentOptimizer(Settings settings) { this.settings = settings; }

    Path run(Path input, Path artifacts) throws Exception {
        long started = System.nanoTime();
        List<PolygonItem> items = BatchBlockStitcher.readItems(input);
        Map<String, PolygonItem> itemMap = new LinkedHashMap<>();
        for (PolygonItem item : items) if (itemMap.put(item.id, item) != null) throw new IOException("输入包含重复 ID: " + item.id);
        double[] plate = NfpSearchSession.plate(input);
        String fingerprint = experimentKey(input, settings.quickMs + ":" + settings.mediumMs + ":" + settings.finalMs);
        Path cache = artifacts.resolve("baseline-cache").resolve(fingerprint);
        Path run = artifacts.resolve("experiments").resolve("run-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(run); Files.createDirectories(cache);
        Path baselinePlan = cache.resolve("nfp").resolve(input.getFileName().toString().replaceFirst("\\.[^.]+$", ".txt"));
        System.out.println("建立/复用固定 baseline，Agent 结果写入独立目录: " + run);
        // baseline 不进入 Agent 会话，也不使用 Profile；原有输出目录不会被覆盖。
        boolean reusable = false;
        if (Files.isRegularFile(baselinePlan)) {
            try { NfpLocalPlan.read(baselinePlan).verifyCoverage(items); reusable = true; }
            catch (IOException incomplete) { System.out.println("baseline NFP 缓存不完整，重新生成。"); }
        }
        if (!reusable) baselinePlan = BatchBlockStitcher.stitchCase(input, cache.resolve("nfp"));
        Measurement baselineFinal = baseline(baselinePlan, input, cache.resolve("final"), settings.finalMs);
        NfpLocalPlan base = NfpLocalPlan.read(baselinePlan);
        WeakBoards weak = weakBoards(baselineFinal, base, itemMap, plate[0] * plate[1]);
        Path baseInput = NFPToBeamSearchBridge.prepareAgentInput(baselinePlan, input, cache.resolve("input"));
        InputKey baselineKey = inputKey(baseInput);
        Set<String> seenPlans = new HashSet<>(); seenPlans.add(NfpPlanSignature.fromResultFile(baselinePlan));
        Map<String, Set<String>> geometryGroups = new HashMap<>();
        geometryGroups.put(baselineKey.geometry(), new HashSet<>(Set.of(baselineKey.withArea())));
        JsonArray history = new JsonArray(); List<Trial> measured = new ArrayList<>();
        JsonObject summary = new JsonObject(); summary.addProperty("experimentKey", fingerprint);
        summary.addProperty("baselinePlan", baselinePlan.toAbsolutePath().toString());
        summary.add("baselineFinal", baselineFinal.quality().deepCopy());
        summary.addProperty("budgetScope", "optimization budgets exclude initial packing; cooperative wall deadline starts after full baseline");
        summary.addProperty("qualityRule", "min Sp, So, physical boardCount, equivalentContainerCount, then max Uagv; same-budget comparisons only");
        summary.addProperty("championPlan", baselinePlan.toAbsolutePath().toString());
        summary.addProperty("championPacking", baselineFinal.directory().toAbsolutePath().toString());
        summary.addProperty("outcome", "baseline_retained"); summary.add("trials", history);
        JsonObject budgets = new JsonObject(); budgets.addProperty("quickMs", settings.quickMs);
        budgets.addProperty("mediumMs", settings.mediumMs); budgets.addProperty("finalMs", settings.finalMs);
        budgets.addProperty("wallMs", settings.wallMs); budgets.addProperty("nfpTrialMs", settings.nfpMs); summary.add("budgets", budgets);
        int sequence = 0;
        long agentStarted = System.nanoTime();
        // 为最终等预算验证预留初始排样估时。不杀排样线程，避免污染求解器全局状态。
        long initialReserve = baselineFinal.quality().getAsJsonObject("timingMs").get("initialPacking").getAsLong() + 1_000;
        long finalReserve = settings.finalMs + initialReserve;
        try (NfpSearchSession session = new NfpSearchSession(plate[0], plate[1], settings.wallMs)) {
            Measurement baselineQuick = null;
            if (session.remainingMs() > finalReserve + settings.quickMs + initialReserve) {
                baselineQuick = baseline(baselinePlan, input, cache.resolve("quick"), settings.quickMs);
                summary.add("baselineQuick", baselineQuick.quality().deepCopy());
            }
            for (int round = 0; round < settings.rounds && baselineQuick != null && measured.size() < settings.quickCandidates; round++) {
                if (session.remainingMs() <= finalReserve + settings.nfpMs + settings.quickMs + initialReserve) break;
                Set<String> unlocked = base.unlock(round, weak.ids());
                List<PolygonItem> localItems = items.stream().filter(item -> unlocked.contains(item.id)).toList();
                if (localItems.size() < 2) break;
                JsonObject report = new JsonObject(); report.addProperty("localItemCount", localItems.size());
                report.addProperty("round", round + 1);
                report.addProperty("plateLength", plate[0]); report.addProperty("plateWidth", plate[1]);
                report.add("weakBoards", weak.boards()); report.add("weakBlocks", base.describe(weak.ids(), itemMap));
                JsonObject feedback = new JsonObject(); feedback.add("fixedBaseline", baselineFinal.quality());
                feedback.add("previousTrials", history.deepCopy());
                List<NfpScoringProfile> portfolio = portfolio(report, feedback, run.resolve("round-" + (round + 1)),
                        session.remainingMs() - finalReserve - settings.nfpMs - settings.quickMs - initialReserve);
                int quota = Math.min(settings.quickCandidates, (int) Math.ceil((round + 1.0) * settings.quickCandidates / settings.rounds));
                List<NfpScoringProfile> attempted = new ArrayList<>();
                List<Block> probeRoots = localItems.stream().map(Block::fromSingle).toList();
                for (NfpScoringProfile profile : portfolio) {
                    if (measured.size() >= quota || session.remainingMs() <= finalReserve + settings.nfpMs + settings.quickMs + initialReserve) break;
                    Path trialDir = run.resolve(String.format("trial-%03d", ++sequence)); Files.createDirectories(trialDir);
                    JsonObject result = new JsonObject(); result.addProperty("round", round + 1); result.add("profile", profile.toJson());
                    result.addProperty("profileId", profile.id()); result.addProperty("unlockedItemCount", unlocked.size());
                    JsonArray ids = new JsonArray(); unlocked.stream().sorted().forEach(ids::add); result.add("unlockedItemIds", ids);
                    history.add(result); long trialStart = System.nanoTime();
                    NfpSelectionTrace trace = new NfpSelectionTrace(profile.id(), profile.rootBeamWidth(), profile.generationCap(), profile.globalPlanWidth());
                    try {
                        String behavior = session.behaviorSignature(profile, probeRoots);
                        if (!behavior.isEmpty() && attempted.stream().anyMatch(prior -> behavior.equals(session.behaviorSignature(prior, probeRoots)))) {
                            result.addProperty("outcome", "skipped_probe_same_ranking");
                            result.addProperty("skipReason", "有限实际候选探针上的排序相同；仅节省预算，不代表数学等价"); continue;
                        }
                        attempted.add(profile); session.startTrial(settings.nfpMs);
                        long nfpStart = System.nanoTime();
                        List<Block> rebuilt = BatchBlockStitcher.buildBlocks(localItems, profile.rootBeamWidth(),
                                new ProfileDrivenNfpSelectionModel(profile), profile.generationCap(), profile.globalPlanWidth(), trace);
                        session.finishTrial();
                        Path local = trialDir.resolve("local.txt"), merged = trialDir.resolve("plan.txt");
                        BatchBlockStitcher.writeBlocks(local, rebuilt); base.merge(local, unlocked, items, merged);
                        result.addProperty("nfpWallMs", (System.nanoTime() - nfpStart) / 1_000_000);
                        String signature = NfpPlanSignature.fromResultFile(merged); result.addProperty("nfpSignature", signature);
                        if (!seenPlans.add(signature)) { result.addProperty("outcome", "skipped_duplicate_nfp"); continue; }
                        Path workpiece = NFPToBeamSearchBridge.prepareAgentInput(merged, input, trialDir.resolve("input"));
                        InputKey key = inputKey(workpiece); result.addProperty("rectangleGeometryKey", key.geometry());
                        result.addProperty("rectangleAreaKey", key.withArea());
                        Set<String> variants = geometryGroups.computeIfAbsent(key.geometry(), ignored -> new HashSet<>());
                        // 每矩形等价类最多两种真实面积分配；不复制或推断不同 UPI 的利用率。
                        if (variants.contains(key.withArea()) || variants.size() >= 2) {
                            result.addProperty("outcome", "skipped_rectangle_group_budget"); continue;
                        }
                        variants.add(key.withArea());
                        if (session.remainingMs() <= finalReserve + settings.quickMs + initialReserve) {
                            result.addProperty("outcome", "skipped_wall_budget"); continue;
                        }
                        Measurement quick = measure(merged, input, trialDir.resolve("quick"), settings.quickMs);
                        result.add("quickQuality", quick.quality()); result.addProperty("quickBudgetMs", settings.quickMs);
                        result.addProperty("quickVsBaseline", compare(quick.quality(), baselineQuick.quality()));
                        result.addProperty("outcome", "quick_measured_not_final");
                        NfpLocalPlan candidatePlan = NfpLocalPlan.read(merged);
                        WeakBoards candidateWeak = weakBoards(quick, candidatePlan, itemMap, plate[0] * plate[1]);
                        result.add("weakBoards", candidateWeak.boards()); result.add("weakBlocks", candidatePlan.describe(candidateWeak.ids(), itemMap));
                        measured.add(new Trial(trialDir, merged, result, quick));
                    } catch (NfpSearchSession.SearchTimeoutException timeout) {
                        result.addProperty("outcome", "skipped_nfp_timeout");
                    } catch (IOException | IllegalArgumentException invalid) {
                        result.addProperty("outcome", "failed_not_measured"); result.addProperty("reason", invalid.getMessage());
                    } finally {
                        session.finishTrial(); result.addProperty("trialWallMs", (System.nanoTime() - trialStart) / 1_000_000);
                        AgentJsonFiles.writeObject(trialDir.resolve("selection-trace.json"), trace.asJson());
                        AgentJsonFiles.writeObject(trialDir.resolve("iteration-result.json"), result);
                        System.out.println("Agent " + trialDir.getFileName() + ": " + result.get("outcome"));
                    }
                }
            }
            // 快速档不硬要求胜过 baseline，保留短预算误排候选的纠错机会。
            measured.sort((left, right) -> compare(left.quick.quality(), right.quick.quality()));
            Trial finalist = null; Measurement finalistMedium = null;
            if (!measured.isEmpty() && session.remainingMs() > finalReserve + settings.mediumMs * 2 + initialReserve * 2) {
                Measurement baselineMedium = baseline(baselinePlan, input, cache.resolve("medium"), settings.mediumMs);
                summary.add("baselineMedium", baselineMedium.quality());
                for (Trial trial : measured.stream().limit(2).toList()) {
                    if (session.remainingMs() <= finalReserve + settings.mediumMs + initialReserve) break;
                    Measurement medium = measure(trial.plan, input, trial.directory.resolve("medium"), settings.mediumMs);
                    trial.result.add("mediumQuality", medium.quality()); trial.result.addProperty("mediumBudgetMs", settings.mediumMs);
                    trial.result.addProperty("outcome", "medium_measured_not_final");
                    AgentJsonFiles.writeObject(trial.directory.resolve("iteration-result.json"), trial.result);
                    if (compare(medium.quality(), baselineMedium.quality()) <= 0
                            && (finalistMedium == null || compare(medium.quality(), finalistMedium.quality()) < 0)) {
                        finalist = trial; finalistMedium = medium;
                    }
                }
            }
            if (finalist != null && session.remainingMs() > finalReserve) {
                Measurement full = measure(finalist.plan, input, finalist.directory.resolve("final"), settings.finalMs);
                finalist.result.add("finalQuality", full.quality()); finalist.result.addProperty("finalBudgetMs", settings.finalMs);
                int comparison = compare(full.quality(), baselineFinal.quality());
                finalist.result.addProperty("outcome", comparison < 0 ? "verified_improved" : comparison == 0 ? "verified_equal" : "verified_regressed");
                AgentJsonFiles.writeObject(finalist.directory.resolve("iteration-result.json"), finalist.result);
                if (comparison < 0) {
                    summary.addProperty("outcome", "verified_improved");
                    summary.addProperty("championPlan", finalist.plan.toAbsolutePath().toString());
                    summary.addProperty("championPacking", full.directory().toAbsolutePath().toString());
                    summary.add("championQuality", full.quality());
                    // 等效容器/末板分配改善不等于节约整张板材，报告中明确区分。
                    summary.addProperty("improvementType", full.quality().get("boardCount").getAsInt()
                            < baselineFinal.quality().get("boardCount").getAsInt()
                            ? "physical_board_reduction" : "priority_or_equivalent_metric_only");
                }
            }
            summary.addProperty("rawCacheHits", session.hits()); summary.addProperty("rawCacheMisses", session.misses());
            summary.addProperty("wallBudgetExceeded", session.remainingMs() == 0);
            summary.addProperty("status", "completed");
        } catch (Exception failure) {
            // 失败时仍交付已验证 baseline，但明确标记中断，不把缺少最终验证写成实验成功。
            summary.addProperty("status", "failed_baseline_retained");
            summary.addProperty("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            summary.addProperty("agentWallMs", (System.nanoTime() - agentStarted) / 1_000_000);
            summary.addProperty("totalWallMsIncludingBaseline", (System.nanoTime() - started) / 1_000_000);
            summary.addProperty("quickMeasuredCount", measured.size());
            if (!summary.has("championQuality")) summary.add("championQuality", baselineFinal.quality());
            AgentJsonFiles.writeObject(run.resolve("summary.json"), summary);
        }
        return run.resolve("summary.json");
    }

    /** 远程设计与本地多样化种子交错；保存来源，不把 fallback 伪装为模型生成。 */
    private List<NfpScoringProfile> portfolio(JsonObject report, JsonObject feedback, Path directory, long availableMs) throws IOException {
        JsonObject record = new JsonObject(); List<NfpScoringProfile> remote = List.of();
        if (settings.useLlm && availableMs > 6_000) {
            try {
                DeepSeekConfig original = DeepSeekConfig.fromCode();
                // 最多三次网络尝试，预留重试时间；不改变其他 Agent 的客户端配置。
                long timeoutMs = Math.min(30_000, Math.max(1_000, (availableMs - 2_000) / 3));
                DeepSeekConfig bounded = new DeepSeekConfig(original.apiKey(), original.apiUri(), original.model(), Duration.ofMillis(timeoutMs));
                remote = new NfpScoringProfileAgent(new DeepSeekClient(bounded)).decideBatch(report, feedback, 4);
                record.addProperty("remoteStatus", "received");
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); throw new IOException("批量设计被中断", failure); }
                record.addProperty("remoteStatus", "failed_using_local_portfolio");
                // 不记录可能含认证信息或远程响应正文的异常消息。
                record.addProperty("failureType", failure.getClass().getSimpleName());
            }
        } else record.addProperty("remoteStatus", settings.useLlm ? "skipped_time_budget" : "disabled_offline");
        List<NfpScoringProfile> local = NfpScoringProfileAgent.localPortfolio(), result = new ArrayList<>();
        // 每轮轮换本地种子先后顺序，避免有限快速名额总被 COMPACT 占用。
        java.util.Collections.rotate(local, -(report.get("round").getAsInt() - 1) % local.size());
        Set<String> ids = new HashSet<>(); JsonArray profiles = new JsonArray();
        for (int i = 0; i < 4 && result.size() < 6; i++) {
            if (i < remote.size()) addProfile(remote.get(i), "llm", ids, result, profiles);
            if (i < 2 && result.size() < 6) addProfile(local.get(i), "local_seed", ids, result, profiles);
        }
        for (NfpScoringProfile seed : local) if (result.size() < 6) addProfile(seed, "local_seed", ids, result, profiles);
        record.add("caseReport", report); record.add("feedback", feedback); record.add("profiles", profiles);
        AgentJsonFiles.writeObject(directory.resolve("profile-batch.json"), record);
        return result;
    }

    private static void addProfile(NfpScoringProfile profile, String source, Set<String> ids,
                                   List<NfpScoringProfile> result, JsonArray profiles) {
        if (!ids.add(profile.id())) return;
        result.add(profile); JsonObject value = profile.toJson(); value.addProperty("source", source); profiles.add(value);
    }
}
