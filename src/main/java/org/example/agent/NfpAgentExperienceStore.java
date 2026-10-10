package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Schema-2.0 experience table with baseline matching and duplicate-profile protection. */
final class NfpAgentExperienceStore {
    static final String FILE_NAME = "nfp-selection-memory.json";
    private final Path file;
    private final JsonObject document;

    private NfpAgentExperienceStore(Path file, JsonObject document) { this.file = file; this.document = document; }

    static NfpAgentExperienceStore open(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            JsonObject value = AgentJsonFiles.readObject(file, "NFP 评分经验文件");
            if ("2.0".equals(string(value, "schemaVersion")) && value.has("experiences") && value.get("experiences").isJsonArray()) return new NfpAgentExperienceStore(file, value);
        }
        JsonObject fresh = new JsonObject(); fresh.addProperty("schemaVersion", "2.0"); fresh.add("experiences", new JsonArray());
        return new NfpAgentExperienceStore(file, fresh);
    }

    int nextIteration(String caseFingerprint, String codeFingerprint) {
        int highest = 0;
        for (var element : entries()) {
            JsonObject entry = element.getAsJsonObject();
            if (matches(entry, caseFingerprint, codeFingerprint) && entry.has("iteration")) highest = Math.max(highest, entry.get("iteration").getAsInt());
        }
        return highest + 1;
    }

    boolean hasProfile(String caseFingerprint, String codeFingerprint, String profileId) {
        for (var element : entries()) {
            JsonObject entry = element.getAsJsonObject();
            if (matches(entry, caseFingerprint, codeFingerprint) && profileId.equals(string(entry, "profileId"))) return true;
        }
        return false;
    }

    /** 同一完整几何方案已经产生记录时，后续 Agent 尝试无需再次进入耗时排样。 */
    boolean hasPlanSignature(String caseFingerprint, String codeFingerprint, String planSignature) {
        for (var element : entries()) {
            JsonObject entry = element.getAsJsonObject();
            if (!matches(entry, caseFingerprint, codeFingerprint) || !entry.has("nfpSummary")) continue;
            JsonObject summary = entry.getAsJsonObject("nfpSummary");
            if (planSignature.equals(string(summary, "planSignature"))) return true;
        }
        return false;
    }

    /** Provides an actual baseline plus compact, unique historical trials to the next model call. */
    JsonObject context(String caseFingerprint, String codeFingerprint) {
        JsonObject context = new JsonObject(); JsonArray trials = new JsonArray(); JsonArray failures = new JsonArray(); Set<String> profileIds = new HashSet<>();
        JsonObject baseline = latestBaseline(caseFingerprint, codeFingerprint);
        if (baseline != null) context.add("fixedBaseline", compact(baseline));
        for (int index = entries().size() - 1; index >= 0 && trials.size() < 12; index--) {
            JsonObject entry = entries().get(index).getAsJsonObject();
            String outcome = string(entry, "outcome");
            // A trial without a baseline or a fallback failure contains no reliable learning signal.
            if (!matches(entry, caseFingerprint, codeFingerprint)
                    ) continue;
            if (outcome.startsWith("skipped_") && failures.size() < 6) {
                failures.add(compactFailure(entry));
                continue;
            }
            if (!("improved".equals(outcome) || "equal".equals(outcome) || "regressed".equals(outcome))) continue;
            String profileId = string(entry, "profileId");
            if (!profileId.isBlank() && profileIds.add(profileId)) trials.add(compact(entry));
        }
        context.add("previousTrials", trials); context.add("structuralFailures", failures);
        JsonArray prohibited = new JsonArray(); profileIds.forEach(prohibited::add); context.add("prohibitedProfileIds", prohibited);
        context.addProperty("rule", "若已有 fixedBaseline，必须基于上轮结果改变评分项；不得返回 prohibitedProfileIds 中的 profileId。");
        return context;
    }

    JsonObject latestBaseline(String caseFingerprint, String codeFingerprint) {
        JsonObject result = null;
        for (var element : entries()) { JsonObject entry = element.getAsJsonObject(); if (matches(entry, caseFingerprint, codeFingerprint) && "baseline".equals(string(entry, "outcome"))) result = entry; }
        return result == null ? null : result.deepCopy();
    }

    /** Saves only one result for a case/code/profile/iteration tuple. */
    void addIfAbsent(JsonObject entry) throws IOException {
        String caseFingerprint = string(entry, "caseFingerprint"), codeFingerprint = string(entry, "codeFingerprint"), profileId = string(entry, "profileId");
        int iteration = entry.has("iteration") ? entry.get("iteration").getAsInt() : -1;
        for (var element : entries()) {
            JsonObject existing = element.getAsJsonObject();
            if (matches(existing, caseFingerprint, codeFingerprint) && profileId.equals(string(existing, "profileId"))
                    && iteration == (existing.has("iteration") ? existing.get("iteration").getAsInt() : -1)) return;
        }
        entries().add(entry);
        while (entries().size() > 120) entries().remove(0);
        AgentJsonFiles.writeObject(file, document);
    }

    private JsonArray entries() { return document.getAsJsonArray("experiences"); }
    private static boolean matches(JsonObject entry, String caseFingerprint, String codeFingerprint) { return caseFingerprint.equals(string(entry, "caseFingerprint")) && codeFingerprint.equals(string(entry, "codeFingerprint")); }
    // 反馈必须包含可执行评分配置，否则模型只能看到结果与 hash，无法判断哪些权重没有效果。
    private static JsonObject compact(JsonObject entry) { JsonObject value = new JsonObject(); for (String name : new String[]{"iteration", "profileId", "profile", "outcome", "packingQuality", "baselineDelta", "nfpSummary", "packingDiagnosis", "hypothesis"}) if (entry.has(name)) value.add(name, entry.get(name).deepCopy()); return value; }
    /** Provides failed structural promises to the next LLM call without treating them as quality observations. */
    private static JsonObject compactFailure(JsonObject entry) { JsonObject value = compact(entry); for (String name : new String[]{"skipReason", "profile"}) if (entry.has(name)) value.add(name, entry.get(name).deepCopy()); return value; }
    private static String string(JsonObject value, String name) { return value.has(name) && value.get(name).isJsonPrimitive() ? value.get(name).getAsString() : ""; }
}
