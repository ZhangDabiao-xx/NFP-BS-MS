package org.example.agent;

import com.google.gson.JsonObject;
import org.example.nfp.BatchBlockStitcher;
import org.example.nfp.NfpAgentConfig;
import org.example.nfp.PolygonItem;
import org.example.nfp.selection.BaselineNfpSelectionModel;
import org.example.nfp.selection.NfpScoringProfile;
import org.example.nfp.selection.NfpSelectionModel;
import org.example.nfp.selection.NfpSelectionTrace;
import org.example.nfp.selection.ProfileDrivenNfpSelectionModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Prepares one safe selection model and writes auditable per-iteration artifacts. */
public final class NfpStitchingAgentWorkflow {
    /** Fallback width for legacy/partial Agent JSON; validated profiles may choose a larger approved width. */
    private static final int DEFAULT_WIDTH = NfpAgentConfig.AGENT_ROOT_BEAM_WIDTH;
    private NfpStitchingAgentWorkflow() { }

    public record Selection(NfpSelectionModel model, NfpScoringProfile profile, JsonObject caseReport,
                            Path iterationDirectory, int iteration, String mode, String fallbackReason,
                            boolean forcedExploration) {
        public boolean agentUsed() { return "agent".equals(mode); }
        /** 仅在显式关闭 Agent 时，才允许走完全不变的 baseline 分支。 */
        public boolean shouldRun() { return "baseline".equals(mode) || agentUsed(); }
        /** 每次 Agent 尝试使用独立输出目录，防止后续试验覆盖可审计结果。 */
        public String outputSegment() { return String.format("iteration-%02d", iteration); }
    }

    /** Creates no client when disabled; when enabled makes one request before NFP search. */
    public static Selection prepareSelectionModel(Path caseJsonFile, Path llmDirectory, boolean enableAgent) throws IOException {
        List<PolygonItem> items = BatchBlockStitcher.readItems(caseJsonFile);
        String fingerprint = fingerprint(caseJsonFile);
        String codeFingerprint = codeFingerprint();
        JsonObject report = caseReport(caseJsonFile, items, fingerprint, codeFingerprint);
        String requiredFamily = NfpAgentConfig.requiredStrategyFamily();
        if (!requiredFamily.isBlank()) {
            // The runner records the requested family in the LLM input and enforces it after parsing.
            report.addProperty("requiredStrategyFamily", requiredFamily);
        }
        NfpAgentExperienceStore store = NfpAgentExperienceStore.open(sharedMemoryPath());
        int iterationNumber = store.nextIteration(fingerprint, codeFingerprint);
        Path agentDirectory = llmDirectory.resolve("nfp-agent");
        // A source change creates a new code fingerprint whose memory iteration starts at one.
        // Do not reuse an existing artifact directory from an older fingerprint.
        while (Files.exists(agentDirectory.resolve(String.format("iteration-%02d", iterationNumber)))) iterationNumber++;
        Path iteration = agentDirectory.resolve(String.format("iteration-%02d", iterationNumber));
        AgentJsonFiles.writeObject(iteration.resolve("profile-request.json"), report);
        if (!enableAgent) return baseline(report, iteration, iterationNumber, "baseline", "NFP Agent 已关闭。");
        try {
            NfpScoringProfile profile = new NfpScoringProfileAgent(new DeepSeekClient(DeepSeekConfig.fromCode()))
                    .decide(report, store.context(fingerprint, codeFingerprint), DEFAULT_WIDTH);
            profile = profile.withRequiredStrategyFamily(requiredFamily, DEFAULT_WIDTH);
            boolean forcedExploration = false;
            // A remote response may be valid yet unchanged. Never rerun a known profile as a new experiment.
            for (int variant = 1; store.hasProfile(fingerprint, codeFingerprint, profile.id()) && variant <= 8; variant++) {
                profile = profile.explorationVariant(iterationNumber + variant, DEFAULT_WIDTH);
                forcedExploration = true;
            }
            if (store.hasProfile(fingerprint, codeFingerprint, profile.id())) {
                throw new IllegalStateException("模型重复返回已测试 Profile，且受限探索变体已耗尽。");
            }
            AgentJsonFiles.writeObject(iteration.resolve("scoring-profile.json"), profile.toJson());
            return new Selection(new ProfileDrivenNfpSelectionModel(profile), profile, report, iteration, iterationNumber,
                    "agent", "", forcedExploration);
        } catch (Exception exception) {
            // 无效远程配置不能伪装成 baseline 试验，更不能消耗第二阶段排样时间。
            return invalid(report, iteration, iterationNumber, limited(exception.getMessage()));
        }
    }

    public static NfpSelectionTrace newTrace(Selection selection, int width, int generationCap, int globalPlanWidth) {
        return new NfpSelectionTrace(selection.model().id(), width, generationCap, globalPlanWidth);
    }

    /** Writes the potentially large trace after geometry has completed, never in an inner loop. */
    public static void recordNfpOutcome(Selection selection, BatchBlockStitcher.StitchingOutcome outcome) throws IOException {
        if (outcome.trace() != null) AgentJsonFiles.writeObject(selection.iterationDirectory().resolve("nfp-selection-trace.json"), outcome.trace().asJson());
        JsonObject metrics = new JsonObject(); metrics.addProperty("nfpTimeMs", outcome.nfpTimeMs()); metrics.addProperty("blockCount", outcome.blocks().size());
        int singles = 0; for (var block : outcome.blocks()) if (block.memberCount() == 1) singles++;
        metrics.addProperty("singleItemCount", singles); metrics.addProperty("combinedBlockCount", outcome.blocks().size() - singles);
        metrics.addProperty("profileId", selection.model().id()); metrics.addProperty("nfpMode", selection.mode());
        recordSearchConfiguration(metrics, selection);
        recordPlanIdentity(metrics, outcome.outputFile());
        // 保存真正交给 bridge 的 NFP 文件，供每次 iteration 独立复核。
        Files.copy(outcome.outputFile(), selection.iterationDirectory().resolve("nfp-result.txt"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        AgentJsonFiles.writeObject(selection.iterationDirectory().resolve("nfp-metrics.json"), metrics);
    }

    /**
     * Records baseline NFP metrics without changing the frozen legacy stitcher API.
     * The baseline writer already emits one indented item ID for every member of a block, so this
     * lightweight parser gives the Agent an auditable baseline composition summary on its next run.
     */
    public static void recordTextNfpOutcome(Selection selection, Path nfpResultFile, long nfpTimeMs) throws IOException {
        List<String> lines = Files.readAllLines(nfpResultFile);
        int blockCount = 0;
        int singleItemCount = 0;
        int currentItemCount = 0;
        boolean insideBlock = false;
        for (String line : lines) {
            if (line.startsWith("block ")) {
                if (insideBlock && currentItemCount == 1) singleItemCount++;
                insideBlock = true;
                blockCount++;
                currentItemCount = 0;
            } else if (line.startsWith("  - id=")) {
                currentItemCount++;
            }
        }
        if (insideBlock && currentItemCount == 1) singleItemCount++;

        JsonObject metrics = new JsonObject();
        metrics.addProperty("nfpTimeMs", nfpTimeMs);
        metrics.addProperty("blockCount", blockCount);
        metrics.addProperty("singleItemCount", singleItemCount);
        metrics.addProperty("combinedBlockCount", blockCount - singleItemCount);
        metrics.addProperty("profileId", selection.model().id());
        metrics.addProperty("nfpMode", selection.mode());
        recordPlanIdentity(metrics, nfpResultFile);
        Files.copy(nfpResultFile, selection.iterationDirectory().resolve("nfp-result.txt"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        AgentJsonFiles.writeObject(selection.iterationDirectory().resolve("nfp-metrics.json"), metrics);
    }

    /** 相同几何方案不进入排样；比较的是完整成员/旋转/坐标签名，而不是统计数量。 */
    public static boolean isDuplicatePlan(Selection selection) throws IOException {
        if (!selection.agentUsed()) return false;
        JsonObject summary = readNfpSummary(selection.iterationDirectory());
        String signature = string(summary, "planSignature");
        if (signature.isBlank()) return false;
        NfpAgentExperienceStore store = NfpAgentExperienceStore.open(sharedMemoryPath());
        return store.hasPlanSignature(string(selection.caseReport(), "caseFingerprint"),
                string(selection.caseReport(), "codeFingerprint"), signature);
    }

    /** 兼容旧调用点；取消按目标成员数拦截结果，重复方案仍由独立签名检查拦截。 */
    public static String unmetStructureTarget(Selection selection) throws IOException {
        // 块规模只是探索偏好，不代表排样质量；合法且不同的 2/3 件组合也应允许评估。
        return "";
    }

    /** 为无效 Profile 或重复 NFP 方案写入终态，而不调用排样器。 */
    public static void recordSkippedOutcome(Selection selection, String outcome, String reason) throws IOException {
        NfpAgentExperienceStore store = NfpAgentExperienceStore.open(sharedMemoryPath());
        JsonObject result = baseResult(selection);
        if (!selection.shouldRun()) {
            // The placeholder baseline model exists only to satisfy the immutable Selection type;
            // do not let it make a rejected remote response look like a baseline profile.
            result.addProperty("profileId", "invalid-profile-" + selection.iteration());
        }
        result.addProperty("outcome", outcome);
        result.addProperty("skipReason", reason);
        result.addProperty("verificationPassed", false);
        JsonObject summary = readNfpSummary(selection.iterationDirectory());
        if (!summary.isEmpty()) result.add("nfpSummary", summary);
        if (selection.profile() != null) {
            result.add("profile", selection.profile().toJson());
            result.addProperty("hypothesis", selection.profile().hypothesis());
        } else result.addProperty("fallbackReason", selection.fallbackReason());
        AgentJsonFiles.writeObject(selection.iterationDirectory().resolve("iteration-result.json"), result);
        // 记录跳过方案，下一次可在 NFP 后立即避免重复排样。
        store.addIfAbsent(result);
    }

    /** Adds a compact, verified quality record. Legacy schema-1 memory is never changed. */
    public static void recordPackingOutcome(Path llmDirectory, Selection selection) throws IOException {
        Path reportPath = llmDirectory.resolve("run-report.json"); if (!Files.isRegularFile(reportPath)) return;
        JsonObject run = AgentJsonFiles.readObject(reportPath, "排样报告"); JsonObject quality = new JsonObject();
        for (String name : List.of("priorityBoardCount", "ordinaryBoardCount", "boardCount", "actualAverageUtilization")) if (run.has(name)) quality.add(name, run.get(name).deepCopy());
        String caseFingerprint = string(selection.caseReport(), "caseFingerprint"); String codeFingerprint = string(selection.caseReport(), "codeFingerprint");
        NfpAgentExperienceStore store = NfpAgentExperienceStore.open(sharedMemoryPath());
        JsonObject baseline = store.latestBaseline(caseFingerprint, codeFingerprint);
        JsonObject result = baseResult(selection); result.add("packingQuality", quality);
        boolean verified = run.has("verification") && run.getAsJsonObject("verification").get("passed").getAsBoolean(); result.addProperty("verificationPassed", verified);
        if ("baseline".equals(selection.mode())) result.addProperty("outcome", "baseline");
        else if (baseline == null) result.addProperty("outcome", "unmeasured_no_baseline");
        else result.addProperty("outcome", compareQuality(baseline.getAsJsonObject("packingQuality"), quality));
        if (baseline != null && selection.agentUsed()) result.add("baselineDelta", qualityDelta(baseline.getAsJsonObject("packingQuality"), quality));
        JsonObject nfpSummary = readNfpSummary(selection.iterationDirectory()); if (!nfpSummary.isEmpty()) result.add("nfpSummary", nfpSummary);
        // Read-only diagnostics explain whether a weak quality result likely came from NFP shape
        // or from the second-stage optimizer exhausting its time budget.
        result.add("packingDiagnosis", packingDiagnosis(run, llmDirectory.getParent()));
        if (selection.profile() != null) { result.add("profile", selection.profile().toJson()); result.addProperty("hypothesis", selection.profile().hypothesis()); }
        else result.addProperty("fallbackReason", selection.fallbackReason());
        AgentJsonFiles.writeObject(selection.iterationDirectory().resolve("iteration-result.json"), result);
        if (verified && ("baseline".equals(selection.mode()) || selection.agentUsed())) store.addIfAbsent(result);
    }

    private static Selection baseline(JsonObject report, Path iteration, int iterationNumber, String mode, String reason) throws IOException {
        JsonObject decision = new JsonObject(); decision.addProperty("nfpMode", "baseline"); decision.addProperty("fallbackReason", reason); decision.addProperty("profileId", BaselineNfpSelectionModel.INSTANCE.id());
        AgentJsonFiles.writeObject(iteration.resolve("scoring-profile.json"), decision);
        return new Selection(BaselineNfpSelectionModel.INSTANCE, null, report, iteration, iterationNumber, mode, reason, false);
    }
    /** 只记录被拒绝的 Agent JSON，绝不借此调用 frozen baseline。 */
    private static Selection invalid(JsonObject report, Path iteration, int iterationNumber, String reason) throws IOException {
        JsonObject decision = new JsonObject(); decision.addProperty("nfpMode", "invalid_profile"); decision.addProperty("fallbackReason", reason);
        decision.addProperty("profileId", "invalid-profile-" + iterationNumber);
        AgentJsonFiles.writeObject(iteration.resolve("scoring-profile.json"), decision);
        return new Selection(BaselineNfpSelectionModel.INSTANCE, null, report, iteration, iterationNumber,
                "invalid", reason, false);
    }
    private static void recordPlanIdentity(JsonObject metrics, Path resultFile) throws IOException {
        metrics.addProperty("planSignature", NfpPlanSignature.fromResultFile(resultFile));
        metrics.addProperty("planDescription", NfpPlanSignature.describe(resultFile));
        JsonObject histogram = new JsonObject();
        for (var entry : NfpPlanSignature.compositeMemberHistogram(resultFile).entrySet()) {
            histogram.addProperty(Integer.toString(entry.getKey()), entry.getValue());
        }
        metrics.add("compositeMemberHistogram", histogram);
    }
    /** Saves executable Agent settings next to results so profile families can be compared without reading prompts. */
    private static void recordSearchConfiguration(JsonObject metrics, Selection selection) {
        if (selection.profile() == null) return;
        JsonObject settings = new JsonObject();
        settings.addProperty("strategyFamily", selection.profile().strategyFamily().name());
        settings.addProperty("rootBeamWidth", selection.profile().rootBeamWidth());
        settings.addProperty("rootCandidateLimit", selection.profile().rootCandidateLimit());
        settings.addProperty("generationCap", selection.profile().generationCap());
        settings.addProperty("globalPlanWidth", selection.profile().globalPlanWidth());
        settings.addProperty("targetCompositeMembers", selection.profile().targetCompositeMembers());
        settings.addProperty("includeNearRectangularRoots", selection.profile().includeNearRectangularRoots());
        metrics.add("agentSearchConfiguration", settings);
    }
    private static JsonObject baseResult(Selection selection) {
        JsonObject result = new JsonObject(); result.addProperty("schemaVersion", "2.0");
        result.addProperty("caseFingerprint", string(selection.caseReport(), "caseFingerprint"));
        result.addProperty("codeFingerprint", string(selection.caseReport(), "codeFingerprint"));
        result.addProperty("iteration", selection.iteration()); result.addProperty("profileId", selection.model().id());
        result.addProperty("forcedExploration", selection.forcedExploration());
        return result;
    }
    private static JsonObject caseReport(Path file, List<PolygonItem> items, String fingerprint, String codeFingerprint) {
        int small = 0, irregular = 0; double area = 0, cavity = 0;
        for (PolygonItem item : items) { area += item.area; if (item.smallItem) small++; if (!item.rectangular) { irregular++; cavity += Math.max(0, item.boxArea - item.area); } }
        JsonObject feature = new JsonObject(); feature.addProperty("itemCount", items.size()); feature.addProperty("smallItemRatio", ratio(small, items.size())); feature.addProperty("irregularItemRatio", ratio(irregular, items.size())); feature.addProperty("estimatedCavityAreaRatio", ratio(cavity, area));
        JsonObject report = new JsonObject(); report.addProperty("schemaVersion", "2.0"); report.addProperty("caseName", file.getFileName().toString()); report.addProperty("caseFingerprint", fingerprint); report.addProperty("codeFingerprint", codeFingerprint); report.add("caseFeatures", feature); return report;
    }
    private static Path sharedMemoryPath() { return Path.of("data", NfpAgentExperienceStore.FILE_NAME); }
    private static double ratio(double a, double b) { return b <= 0 ? 0 : a / b; }
    private static String string(JsonObject value, String name) { return value.has(name) ? value.get(name).getAsString() : ""; }
    private static String fingerprint(Path path) throws IOException { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); } catch (Exception exception) { throw new IOException("无法计算案例指纹", exception); } }
    /** Hash only NFP selection sources so incompatible experiments are visibly distinguishable. */
    private static String codeFingerprint() throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String relative : List.of("src/main/java/org/example/nfp/BatchBlockStitcher.java",
                    "src/main/java/org/example/nfp/PolygonStitcher.java",
                    "src/main/java/org/example/nfp/selection/NfpScoringProfile.java",
                    "src/main/java/org/example/nfp/selection/NfpFeatureExtractor.java",
                    "src/main/java/org/example/nfp/selection/ProfileDrivenNfpSelectionModel.java",
                    "src/main/java/org/example/agent/NfpStitchingAgentWorkflow.java",
                    "src/main/java/org/example/agent/NfpPlanSignature.java",
                    "src/main/java/org/example/application/IntegratedPackingApplication.java",
                    "src/main/java/org/example/agent/NfpAgentExperimentApplication.java",
                    "src/main/java/org/example/agent/NfpScoringProfileAgent.java",
                    "src/main/java/org/example/agent/NfpAgentExperienceStore.java",
                    "src/main/java/org/example/nfp/NfpAgentConfig.java")) {
                Path file = Path.of(relative); if (Files.isRegularFile(file)) digest.update(Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) { throw new IOException("无法计算 NFP 代码指纹", exception); }
    }
    private static String limited(String value) { if (value == null) return "unknown"; value = value.replaceAll("\\s+", " ").strip(); return value.length() > 200 ? value.substring(0, 200) : value; }

    private static JsonObject readNfpSummary(Path iteration) throws IOException { Path path = iteration.resolve("nfp-metrics.json"); return Files.isRegularFile(path) ? AgentJsonFiles.readObject(path, "NFP 指标") : new JsonObject(); }
    private static String compareQuality(JsonObject baseline, JsonObject current) { int comparison = qualityComparison(baseline, current); return comparison < 0 ? "improved" : comparison > 0 ? "regressed" : "equal"; }
    /** Negative means candidate is better, following the project's business priority. */
    private static int qualityComparison(JsonObject baseline, JsonObject current) { for (String name : List.of("priorityBoardCount", "ordinaryBoardCount", "boardCount")) { int comparison = Double.compare(number(current, name), number(baseline, name)); if (comparison != 0) return comparison; } return -Double.compare(number(current, "actualAverageUtilization"), number(baseline, "actualAverageUtilization")); }
    private static JsonObject qualityDelta(JsonObject baseline, JsonObject current) { JsonObject value = new JsonObject(); for (String name : List.of("priorityBoardCount", "ordinaryBoardCount", "boardCount", "actualAverageUtilization")) value.addProperty(name, number(current, name) - number(baseline, name)); return value; }
    private static double number(JsonObject value, String name) { return value.has(name) ? value.get(name).getAsDouble() : 0.0; }

    /** Extracts a bounded summary of the most weakly utilized boards without changing packing output. */
    private static JsonObject packingDiagnosis(JsonObject run, Path casePackingDirectory) throws IOException {
        JsonObject diagnosis = new JsonObject();
        if (run.has("repackMonitoring")) {
            JsonObject monitoring = run.getAsJsonObject("repackMonitoring");
            if (monitoring.has("ordinary")) {
                JsonObject ordinary = monitoring.getAsJsonObject("ordinary");
                for (String name : List.of("timeLimitReached", "boardCountBefore", "boardCountAfter", "boardReductions")) {
                    if (ordinary.has(name)) diagnosis.add(name, ordinary.get(name).deepCopy());
                }
            }
        }
        Path statistics = casePackingDirectory == null ? null : casePackingDirectory.resolve("statistics.csv");
        if (statistics == null || !Files.isRegularFile(statistics)) return diagnosis;
        List<BoardUtilization> values = new ArrayList<>();
        List<String> lines = Files.readAllLines(statistics);
        for (int index = 1; index < lines.size(); index++) {
            String[] columns = lines.get(index).split(",", -1);
            if (columns.length < 8) continue;
            Double utilization = percent(columns[7]);
            if (utilization != null) values.add(new BoardUtilization(columns[1].strip(), utilization));
        }
        values.sort(Comparator.comparingDouble(BoardUtilization::utilization));
        if (!values.isEmpty()) {
            diagnosis.addProperty("minimumBoardUtilization", values.get(0).utilization());
            com.google.gson.JsonArray weakest = new com.google.gson.JsonArray();
            for (int index = 0; index < Math.min(5, values.size()); index++) {
                JsonObject board = new JsonObject(); board.addProperty("board", values.get(index).board());
                board.addProperty("utilization", values.get(index).utilization()); weakest.add(board);
            }
            diagnosis.add("weakestBoards", weakest);
        }
        return diagnosis;
    }

    private static Double percent(String raw) {
        try {
            String value = raw.strip().replace("%", "");
            return Double.parseDouble(value) / 100.0;
        } catch (NumberFormatException exception) { return null; }
    }

    private record BoardUtilization(String board, double utilization) {
    }
}
