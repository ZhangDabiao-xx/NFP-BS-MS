package org.example.agent;

import com.google.gson.JsonObject;
import org.example.nfp.BatchBlockStitcher;
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
import java.util.HexFormat;
import java.util.List;

/** Prepares one safe selection model and writes auditable per-iteration artifacts. */
public final class NfpStitchingAgentWorkflow {
    private static final int DEFAULT_WIDTH = 5;
    private NfpStitchingAgentWorkflow() { }

    public record Selection(NfpSelectionModel model, NfpScoringProfile profile, JsonObject caseReport,
                            Path iterationDirectory, int iteration, String mode, String fallbackReason,
                            boolean forcedExploration) {
        public boolean agentUsed() { return "agent".equals(mode); }
    }

    /** Creates no client when disabled; when enabled makes one request before NFP search. */
    public static Selection prepareSelectionModel(Path caseJsonFile, Path llmDirectory, boolean enableAgent) throws IOException {
        List<PolygonItem> items = BatchBlockStitcher.readItems(caseJsonFile);
        String fingerprint = fingerprint(caseJsonFile);
        String codeFingerprint = codeFingerprint();
        JsonObject report = caseReport(caseJsonFile, items, fingerprint, codeFingerprint);
        NfpAgentExperienceStore store = NfpAgentExperienceStore.open(sharedMemoryPath());
        int iterationNumber = store.nextIteration(fingerprint, codeFingerprint);
        Path iteration = llmDirectory.resolve("nfp-agent").resolve(String.format("iteration-%02d", iterationNumber));
        AgentJsonFiles.writeObject(iteration.resolve("profile-request.json"), report);
        if (!enableAgent) return baseline(report, iteration, iterationNumber, "baseline", "NFP Agent 已关闭。");
        try {
            NfpScoringProfile profile = new NfpScoringProfileAgent(new DeepSeekClient(DeepSeekConfig.fromCode()))
                    .decide(report, store.context(fingerprint, codeFingerprint), DEFAULT_WIDTH);
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
            return baseline(report, iteration, iterationNumber, "fallback", limited(exception.getMessage()));
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
        metrics.addProperty("singleItemCount", singles); metrics.addProperty("profileId", selection.model().id()); metrics.addProperty("nfpMode", selection.mode());
        AgentJsonFiles.writeObject(selection.iterationDirectory().resolve("nfp-metrics.json"), metrics);
    }

    /** Adds a compact, verified quality record. Legacy schema-1 memory is never changed. */
    public static void recordPackingOutcome(Path llmDirectory, Selection selection) throws IOException {
        Path reportPath = llmDirectory.resolve("run-report.json"); if (!Files.isRegularFile(reportPath)) return;
        JsonObject run = AgentJsonFiles.readObject(reportPath, "排样报告"); JsonObject quality = new JsonObject();
        for (String name : List.of("priorityBoardCount", "ordinaryBoardCount", "boardCount", "actualAverageUtilization")) if (run.has(name)) quality.add(name, run.get(name).deepCopy());
        String caseFingerprint = string(selection.caseReport(), "caseFingerprint"); String codeFingerprint = string(selection.caseReport(), "codeFingerprint");
        NfpAgentExperienceStore store = NfpAgentExperienceStore.open(sharedMemoryPath());
        JsonObject baseline = store.latestBaseline(caseFingerprint, codeFingerprint);
        JsonObject result = new JsonObject(); result.addProperty("schemaVersion", "2.0"); result.addProperty("caseFingerprint", caseFingerprint);
        result.addProperty("codeFingerprint", codeFingerprint); result.addProperty("iteration", selection.iteration());
        result.addProperty("profileId", selection.model().id()); result.add("packingQuality", quality);
        boolean verified = run.has("verification") && run.getAsJsonObject("verification").get("passed").getAsBoolean(); result.addProperty("verificationPassed", verified);
        if ("baseline".equals(selection.mode())) result.addProperty("outcome", "baseline");
        else if (!selection.agentUsed()) result.addProperty("outcome", "fallback");
        else if (baseline == null) result.addProperty("outcome", "unmeasured_no_baseline");
        else result.addProperty("outcome", compareQuality(baseline.getAsJsonObject("packingQuality"), quality));
        if (baseline != null && selection.agentUsed()) result.add("baselineDelta", qualityDelta(baseline.getAsJsonObject("packingQuality"), quality));
        JsonObject nfpSummary = readNfpSummary(selection.iterationDirectory()); if (!nfpSummary.isEmpty()) result.add("nfpSummary", nfpSummary);
        result.addProperty("forcedExploration", selection.forcedExploration());
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
                    "src/main/java/org/example/agent/NfpStitchingAgentWorkflow.java",
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
}
