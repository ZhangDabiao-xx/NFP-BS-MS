package org.example.nfp.selection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** In-memory trace collector. It deliberately does no file I/O inside geometry loops. */
public final class NfpSelectionTrace {
    private final JsonObject root = new JsonObject();
    public NfpSelectionTrace(String modelId, int width, int generationCap, int globalPlanWidth) {
        root.addProperty("schemaVersion", "2.0"); root.addProperty("modelId", modelId); root.addProperty("rootBeamWidth", width);
        root.addProperty("generationCap", generationCap); root.addProperty("globalPlanWidth", globalPlanWidth); root.add("events", new JsonArray());
    }
    public void event(String type, String signature, double score, int generated, int retained) {
        JsonObject event = new JsonObject(); event.addProperty("type", type); event.addProperty("signature", signature); event.addProperty("score", score); event.addProperty("generated", generated); event.addProperty("retained", retained); root.getAsJsonArray("events").add(event);
    }
    public JsonObject asJson() { return root.deepCopy(); }
}
