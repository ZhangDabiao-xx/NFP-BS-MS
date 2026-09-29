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
                            Path iterationDirectory, String mode, String fallbackReason) {
        public boolean agentUsed() { return "agent".equals(mode); }
    }

    /** Creates no client when disabled; when enabled makes one request before NFP search. */
    public static Selection prepareSelectionModel(Path caseJsonFile, Path llmDirectory, boolean enableAgent) throws IOException {
        List<PolygonItem> items = BatchBlockStitcher.readItems(caseJsonFile);
        String fingerprint = fingerprint(caseJsonFile);
        JsonObject report = caseReport(caseJsonFile, items, fingerprint);
        Path iteration = llmDirectory.resolve("nfp-agent").resolve("iteration-01");
        AgentJsonFiles.writeObject(iteration.resolve("profile-request.json"), report);
        if (!enableAgent) return baseline(report, iteration, "NFP Agent 已关闭。");
        try {
            NfpAgentExperienceStore store = NfpAgentExperienceStore.open(sharedMemoryPath());
            NfpScoringProfile profile = new NfpScoringProfileAgent(new DeepSeekClient(DeepSeekConfig.fromCode()))
                    .decide(report, store.context(fingerprint), DEFAULT_WIDTH);
            AgentJsonFiles.writeObject(iteration.resolve("scoring-profile.json"), profile.toJson());
            return new Selection(new ProfileDrivenNfpSelectionModel(profile), profile, report, iteration, "agent", "");
        } catch (Exception exception) {
            return baseline(report, iteration, limited(exception.getMessage()));
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
        JsonObject result = new JsonObject(); result.addProperty("schemaVersion", "2.0"); result.addProperty("caseFingerprint", string(selection.caseReport(), "caseFingerprint"));
        result.addProperty("codeFingerprint", codeFingerprint());
        result.addProperty("profileId", selection.model().id()); result.addProperty("outcome", selection.agentUsed() ? "recorded" : "fallback"); result.add("packingQuality", quality);
        boolean verified = run.has("verification") && run.getAsJsonObject("verification").get("passed").getAsBoolean(); result.addProperty("verificationPassed", verified);
        if (selection.profile() != null) result.add("profile", selection.profile().toJson()); else result.addProperty("fallbackReason", selection.fallbackReason());
        AgentJsonFiles.writeObject(selection.iterationDirectory().resolve("iteration-result.json"), result);
        if (selection.agentUsed() && verified) NfpAgentExperienceStore.open(sharedMemoryPath()).add(result);
    }

    private static Selection baseline(JsonObject report, Path iteration, String reason) throws IOException {
        JsonObject decision = new JsonObject(); decision.addProperty("nfpMode", "baseline"); decision.addProperty("fallbackReason", reason); decision.addProperty("profileId", BaselineNfpSelectionModel.INSTANCE.id());
        AgentJsonFiles.writeObject(iteration.resolve("scoring-profile.json"), decision);
        return new Selection(BaselineNfpSelectionModel.INSTANCE, null, report, iteration, "baseline", reason);
    }
    private static JsonObject caseReport(Path file, List<PolygonItem> items, String fingerprint) {
        int small = 0, irregular = 0; double area = 0, cavity = 0;
        for (PolygonItem item : items) { area += item.area; if (item.smallItem) small++; if (!item.rectangular) { irregular++; cavity += Math.max(0, item.boxArea - item.area); } }
        JsonObject feature = new JsonObject(); feature.addProperty("itemCount", items.size()); feature.addProperty("smallItemRatio", ratio(small, items.size())); feature.addProperty("irregularItemRatio", ratio(irregular, items.size())); feature.addProperty("estimatedCavityAreaRatio", ratio(cavity, area));
        JsonObject report = new JsonObject(); report.addProperty("schemaVersion", "2.0"); report.addProperty("caseName", file.getFileName().toString()); report.addProperty("caseFingerprint", fingerprint); report.add("caseFeatures", feature); return report;
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
                    "src/main/java/org/example/nfp/selection/NfpScoringProfile.java")) {
                Path file = Path.of(relative); if (Files.isRegularFile(file)) digest.update(Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) { throw new IOException("无法计算 NFP 代码指纹", exception); }
    }
    private static String limited(String value) { if (value == null) return "unknown"; value = value.replaceAll("\\s+", " ").strip(); return value.length() > 200 ? value.substring(0, 200) : value; }
}
