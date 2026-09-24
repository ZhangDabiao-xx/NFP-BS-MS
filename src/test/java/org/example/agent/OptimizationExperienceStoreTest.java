package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/** 不调用模型 API 的经验文件回归测试。 */
public final class OptimizationExperienceStoreTest {

    private OptimizationExperienceStoreTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("optimization-experience-test-");
        try {
            OptimizationExperienceStore store = OptimizationExperienceStore.open(directory);
            JsonObject decision = decision();
            JsonObject changes = changes();
            JsonObject validation = safeButIneffectiveValidation();

            store.record(1, decision, changes, validation);
            // 同一轮重复写入不应导致经验重复。
            store.record(1, decision, changes, validation);

            OptimizationExperienceStore reopened = OptimizationExperienceStore.open(directory);
            JsonObject context = reopened.decisionContext();
            JsonArray experiences = context.getAsJsonArray("recentExperiences");
            require(experiences.size() == 1, "同一 iteration 只能保存一条经验。");

            JsonObject experience = experiences.get(0).getAsJsonObject();
            require("safe_but_ineffective".equals(experience.get("outcome").getAsString()),
                    "应识别为安全但无收益的实验。");
            require("space-ordering".equals(experience.get("targetId").getAsString()),
                    "应保留本轮选择的策略目标。");
            require(experience.getAsJsonArray("editPointIds").size() == 1,
                    "应保留实际修改点，供下一轮避免重复。");
            require(experience.getAsJsonObject("qualityDelta").has("priorityBoardCount"),
                    "应记录解质量指标变化。");
            System.out.println("OptimizationExperienceStoreTest passed.");
        } finally {
            Files.deleteIfExists(directory.resolve(OptimizationExperienceStore.FILE_NAME));
            Files.deleteIfExists(directory);
        }
    }

    private static JsonObject decision() {
        JsonObject selectedPlan = new JsonObject();
        selectedPlan.addProperty("targetId", "space-ordering");
        selectedPlan.addProperty("summary", "调整空间选择顺序。");
        JsonObject decision = new JsonObject();
        decision.add("selectedPlan", selectedPlan);
        return decision;
    }

    private static JsonObject changes() {
        JsonObject change = new JsonObject();
        change.addProperty("editPointId", "space-comparator-order");
        JsonArray values = new JsonArray();
        values.add(change);
        JsonObject changes = new JsonObject();
        changes.add("changes", values);
        return changes;
    }

    private static JsonObject safeButIneffectiveValidation() {
        JsonObject checks = new JsonObject();
        checks.addProperty("safetyPassed", true);
        checks.addProperty("effectivenessPassed", false);
        checks.addProperty("baselinePriorityBoardCount", 5);
        checks.addProperty("candidatePriorityBoardCount", 5);
        checks.addProperty("baselineOrdinaryBoardCount", 72);
        checks.addProperty("candidateOrdinaryBoardCount", 72);
        checks.addProperty("baselineBoardCount", 76.0d);
        checks.addProperty("candidateBoardCount", 76.0d);
        checks.addProperty("baselineActualAverageUtilization", 0.93d);
        checks.addProperty("candidateActualAverageUtilization", 0.93d);
        checks.addProperty("baselineTotalPackingTimeMs", 600_000L);
        checks.addProperty("candidateTotalPackingTimeMs", 599_000L);
        JsonObject validation = new JsonObject();
        validation.add("qualityChecks", checks);
        validation.addProperty("passed", false);
        return validation;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
