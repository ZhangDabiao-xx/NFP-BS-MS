package org.example.nfp.selection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the profile boundary; no test sends a network request. */
class NfpScoringProfileValidationTest {
    @Test
    void acceptsWhitelistedProfileAndProducesStableHashAcrossTermOrder() {
        JsonObject first = profile("normalizedSBox", "sArea");
        JsonObject second = profile("sArea", "normalizedSBox");
        NfpScoringProfile left = NfpScoringProfile.fromJson(first, 5);
        NfpScoringProfile right = NfpScoringProfile.fromJson(second, 5);
        assertEquals(left.id(), right.id());
        assertTrue(left.score(NfpSelectionStage.PLACEMENT, java.util.Map.of("normalizedSBox", 0.8, "sArea", 0.2)) > 0);
    }

    @Test
    void rejectsUnknownFeatureAndNonFiniteWeight() {
        JsonObject unknown = profile("notJavaCode", "sArea");
        assertThrows(IllegalArgumentException.class, () -> NfpScoringProfile.fromJson(unknown, 5));
        JsonObject invalid = profile("normalizedSBox", "sArea");
        invalid.getAsJsonArray("placementScore").get(0).getAsJsonObject().addProperty("weight", "NaN");
        assertThrows(IllegalArgumentException.class, () -> NfpScoringProfile.fromJson(invalid, 5));
    }

    private static JsonObject profile(String firstPlacementFeature, String secondPlacementFeature) {
        JsonObject value = new JsonObject();
        value.add("placementScore", terms(firstPlacementFeature, secondPlacementFeature));
        value.add("rootBeamScore", terms("fillRate", "cavityInsertionRatio"));
        value.add("globalPlanScore", terms("coveredItemRatio", "meanBlockFillRate"));
        value.addProperty("diversityWeight", 0.1); value.addProperty("explorationSlots", 1);
        value.addProperty("allowAllSingles", false); value.addProperty("reason", "test"); value.addProperty("hypothesis", "test");
        return value;
    }

    private static JsonArray terms(String first, String second) {
        JsonArray array = new JsonArray(); array.add(term(first, 0.5)); array.add(term(second, 0.2)); return array;
    }
    private static JsonObject term(String feature, double weight) {
        JsonObject value = new JsonObject(); value.addProperty("feature", feature); value.addProperty("transform", "identity"); value.addProperty("weight", weight); value.addProperty("threshold", 0.0); return value;
    }
}
