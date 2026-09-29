package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Schema-2.0 compact experience table; legacy nfp-stitch-memory.json is intentionally untouched. */
final class NfpAgentExperienceStore {
    static final String FILE_NAME = "nfp-selection-memory.json";
    private final Path file; private final JsonObject document;
    private NfpAgentExperienceStore(Path file, JsonObject document) { this.file = file; this.document = document; }
    static NfpAgentExperienceStore open(Path file) throws IOException {
        if (Files.isRegularFile(file)) { JsonObject value = AgentJsonFiles.readObject(file, "NFP 评分经验文件"); if ("2.0".equals(string(value, "schemaVersion")) && value.has("experiences") && value.get("experiences").isJsonArray()) return new NfpAgentExperienceStore(file, value); }
        JsonObject fresh = new JsonObject(); fresh.addProperty("schemaVersion", "2.0"); fresh.add("experiences", new JsonArray()); return new NfpAgentExperienceStore(file, fresh);
    }
    JsonObject context(String caseFingerprint) {
        JsonArray selected = new JsonArray(); JsonArray values = document.getAsJsonArray("experiences");
        for (int i = Math.max(0, values.size() - 12); i < values.size(); i++) { JsonObject entry = values.get(i).getAsJsonObject(); if (caseFingerprint.equals(string(entry,"caseFingerprint"))) selected.add(entry.deepCopy()); }
        JsonObject context = new JsonObject(); context.add("sameCaseExperiences", selected); context.addProperty("note", "只使用已验证的 profile 结果；fallback 不作为成功经验。"); return context;
    }
    void add(JsonObject entry) throws IOException { JsonArray values = document.getAsJsonArray("experiences"); values.add(entry); while (values.size() > 80) values.remove(0); AgentJsonFiles.writeObject(file, document); }
    private static String string(JsonObject value, String name) { return value.has(name) && value.get(name).isJsonPrimitive() ? value.get(name).getAsString() : ""; }
}
