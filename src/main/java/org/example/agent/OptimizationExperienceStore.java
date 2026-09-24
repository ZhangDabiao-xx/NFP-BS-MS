package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 保存一个案例的优化实验经验，供之后的 Decision Agent 使用。
 *
 * <p>LLM API 本身没有跨请求记忆。本类只持久化可验证的“方案、修改点、验证结论和
 * 指标变化”，而不保存完整提示词、完整源码或大段模型回复，避免经验文件和提示词无限增长。</p>
 */
final class OptimizationExperienceStore {

    static final String FILE_NAME = "optimization-memory.json";

    /** 文件中保留的最大实验记录数；较早记录对当前策略的参考价值较低。 */
    private static final int MAX_STORED_EXPERIENCES = 30;

    /** 单次决策最多读取最近几条经验，以控制 API 输入长度。 */
    private static final int MAX_CONTEXT_EXPERIENCES = 6;

    /** 模型生成的摘要或异常说明写入经验文件前的最大长度。 */
    private static final int MAX_TEXT_LENGTH = 400;

    private final Path file;
    private final JsonObject document;

    private OptimizationExperienceStore(Path file, JsonObject document) {
        this.file = file;
        this.document = document;
    }

    /** 打开案例的经验文件；首次使用时创建空文件结构。 */
    static OptimizationExperienceStore open(Path llmDirectory) throws IOException {
        Path file = llmDirectory.resolve(FILE_NAME);
        JsonObject document;
        if (Files.isRegularFile(file)) {
            document = AgentJsonFiles.readObject(file, "优化经验文件");
        } else {
            document = new JsonObject();
            document.addProperty("schemaVersion", "1.0");
            document.add("experiences", new JsonArray());
        }
        ensureExperiences(document);
        return new OptimizationExperienceStore(file, document);
    }

    /**
     * 将旧 iteration 目录中的结构化证据回填到经验文件。
     *
     * <p>已有记录不会重复写入，因此可在每次工作流启动时安全调用。</p>
     */
    void importExistingIterations(Path llmDirectory) throws IOException {
        if (!Files.isDirectory(llmDirectory)) {
            return;
        }

        Set<Integer> knownIterations = recordedIterations();
        List<Path> directories;
        try (Stream<Path> paths = Files.list(llmDirectory)) {
            directories = paths.filter(Files::isDirectory)
                    .filter(path -> iterationNumber(path.getFileName().toString()) > 0)
                    .sorted(Comparator.comparingInt(path -> iterationNumber(path.getFileName().toString())))
                    .toList();
        }

        for (Path directory : directories) {
            int iteration = iterationNumber(directory.getFileName().toString());
            if (knownIterations.contains(iteration)) {
                continue;
            }
            Path decisionPath = directory.resolve("decision.json");
            Path validationPath = directory.resolve("validation.json");
            if (!Files.isRegularFile(decisionPath) || !Files.isRegularFile(validationPath)) {
                continue;
            }
            JsonObject decision = AgentJsonFiles.readObject(decisionPath, "历史优化决策");
            JsonObject validation = AgentJsonFiles.readObject(validationPath, "历史验证结果");
            JsonObject changes = Files.isRegularFile(directory.resolve("code-changes.json"))
                    ? AgentJsonFiles.readObject(directory.resolve("code-changes.json"), "历史代码修改")
                    : null;
            addExperience(iteration, decision, changes, validation);
            knownIterations.add(iteration);
        }
        save();
    }

    /** 记录本轮已经结束的实验；相同 iteration 不会重复加入。 */
    void record(int iteration,
                JsonObject decision,
                JsonObject changeSet,
                JsonObject validation) throws IOException {
        if (iteration <= 0 || recordedIterations().contains(iteration)) {
            return;
        }
        addExperience(iteration, decision, changeSet, validation);
        save();
    }

    /** 返回给 Decision Agent 的精简经验，而不是整个历史文件。 */
    JsonObject decisionContext() {
        JsonObject context = new JsonObject();
        context.addProperty("purpose", "以下是同一案例先前优化实验的确定性结果。"
                + "避免重复已验证无效的相同修改点；只有更换假设或修改点时才可重新尝试。");
        context.addProperty("qualityPriority", "Sp -> So -> N -> actualAverageUtilization -> totalPackingTime");

        JsonArray history = experiences();
        JsonArray recent = new JsonArray();
        int start = Math.max(0, history.size() - MAX_CONTEXT_EXPERIENCES);
        for (int index = start; index < history.size(); index++) {
            recent.add(compactExperience(history.get(index).getAsJsonObject()));
        }
        context.add("recentExperiences", recent);
        context.add("strategySummary", strategySummary(history));
        return context;
    }

    private void addExperience(int iteration,
                               JsonObject decision,
                               JsonObject changeSet,
                               JsonObject validation) {
        JsonObject entry = new JsonObject();
        entry.addProperty("iteration", iteration);
        entry.addProperty("outcome", outcome(validation));
        entry.addProperty("targetId", selectedTargetId(decision));
        entry.addProperty("planSummary", limited(planSummary(decision)));
        entry.add("editPointIds", editPointIds(changeSet));
        entry.add("qualityDelta", qualityDelta(validation));
        entry.addProperty("lesson", lesson(decision, changeSet, validation));

        JsonObject failure = failureSummary(validation);
        if (!failure.isEmpty()) {
            entry.add("failure", failure);
        }

        JsonArray values = experiences();
        values.add(entry);
        while (values.size() > MAX_STORED_EXPERIENCES) {
            values.remove(0);
        }
    }

    private JsonObject compactExperience(JsonObject entry) {
        JsonObject compact = new JsonObject();
        copy(entry, compact, "iteration");
        copy(entry, compact, "outcome");
        copy(entry, compact, "targetId");
        copy(entry, compact, "planSummary");
        copy(entry, compact, "editPointIds");
        copy(entry, compact, "qualityDelta");
        copy(entry, compact, "lesson");
        copy(entry, compact, "failure");
        return compact;
    }

    private JsonObject strategySummary(JsonArray history) {
        JsonObject summary = new JsonObject();
        for (JsonElement element : history) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String targetId = stringValue(entry, "targetId");
            if (targetId.isBlank()) {
                continue;
            }
            JsonObject target = summary.has(targetId)
                    ? summary.getAsJsonObject(targetId) : new JsonObject();
            increment(target, "attemptCount");
            String outcome = stringValue(entry, "outcome");
            if ("accepted".equals(outcome)) {
                increment(target, "acceptedCount");
            } else if ("safe_but_ineffective".equals(outcome)) {
                increment(target, "ineffectiveCount");
            } else {
                increment(target, "failedCount");
            }
            summary.add(targetId, target);
        }
        return summary;
    }

    private JsonArray editPointIds(JsonObject changeSet) {
        JsonArray result = new JsonArray();
        if (changeSet == null || !changeSet.has("changes") || !changeSet.get("changes").isJsonArray()) {
            return result;
        }
        for (JsonElement element : changeSet.getAsJsonArray("changes")) {
            if (element.isJsonObject()) {
                String editPointId = stringValue(element.getAsJsonObject(), "editPointId");
                if (!editPointId.isBlank()) {
                    result.add(editPointId);
                }
            }
        }
        return result;
    }

    private JsonObject qualityDelta(JsonObject validation) {
        JsonObject result = new JsonObject();
        JsonObject checks = validation == null ? null : validation.getAsJsonObject("qualityChecks");
        if (checks == null) {
            return result;
        }
        addDifference(checks, result, "priorityBoardCount",
                "baselinePriorityBoardCount", "candidatePriorityBoardCount");
        addDifference(checks, result, "ordinaryBoardCount",
                "baselineOrdinaryBoardCount", "candidateOrdinaryBoardCount");
        addDifference(checks, result, "boardCount",
                "baselineBoardCount", "candidateBoardCount");
        addDifference(checks, result, "actualAverageUtilization",
                "baselineActualAverageUtilization", "candidateActualAverageUtilization");
        addDifference(checks, result, "totalPackingTimeMs",
                "baselineTotalPackingTimeMs", "candidateTotalPackingTimeMs");
        return result;
    }

    private void addDifference(JsonObject source,
                               JsonObject target,
                               String resultName,
                               String baselineName,
                               String candidateName) {
        if (!source.has(baselineName) || !source.has(candidateName)) {
            return;
        }
        target.addProperty(resultName,
                source.get(candidateName).getAsDouble() - source.get(baselineName).getAsDouble());
    }

    private String outcome(JsonObject validation) {
        if (booleanValue(validation, "passed")) {
            return "accepted";
        }
        JsonObject checks = validation == null ? null : validation.getAsJsonObject("qualityChecks");
        if (checks != null && booleanValue(checks, "safetyPassed")
                && checks.has("effectivenessPassed")
                && !checks.get("effectivenessPassed").getAsBoolean()) {
            return "safe_but_ineffective";
        }
        return "verification_failed";
    }

    private String lesson(JsonObject decision, JsonObject changeSet, JsonObject validation) {
        String targetId = selectedTargetId(decision);
        JsonArray points = editPointIds(changeSet);
        String pointText = points.isEmpty() ? "未产生可应用修改点" : points.toString();
        String result = outcome(validation);
        if ("accepted".equals(result)) {
            return limited("该策略在当前案例已通过确定性验证，可作为后续优化的基线。");
        }
        if ("safe_but_ineffective".equals(result)) {
            return limited("策略 " + targetId + " 在修改点 " + pointText
                    + " 上可运行但未改善质量指标；下一轮应更换策略假设或修改点，避免同类微调。");
        }
        return limited("策略 " + targetId + " 在修改点 " + pointText
                + " 未通过验证；先依据失败原因修复，再考虑该方向。");
    }

    private JsonObject failureSummary(JsonObject validation) {
        JsonObject result = new JsonObject();
        if (validation == null) {
            return result;
        }
        String stage = stringValue(validation, "stage");
        String reason = stringValue(validation, "failureReason");
        if (!stage.isBlank()) {
            result.addProperty("stage", limited(stage));
        }
        if (!reason.isBlank()) {
            result.addProperty("reason", limited(reason));
        }
        return result;
    }

    private Set<Integer> recordedIterations() {
        Set<Integer> result = new HashSet<>();
        for (JsonElement element : experiences()) {
            if (element.isJsonObject() && element.getAsJsonObject().has("iteration")) {
                result.add(element.getAsJsonObject().get("iteration").getAsInt());
            }
        }
        return result;
    }

    private int iterationNumber(String name) {
        if (!name.matches("iteration-\\d+")) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring("iteration-".length()));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private JsonArray experiences() {
        return document.getAsJsonArray("experiences");
    }

    private void save() throws IOException {
        AgentJsonFiles.writeObject(file, document);
    }

    private static void ensureExperiences(JsonObject value) {
        if (!value.has("schemaVersion")) {
            value.addProperty("schemaVersion", "1.0");
        }
        if (!value.has("experiences") || !value.get("experiences").isJsonArray()) {
            value.add("experiences", new JsonArray());
        }
    }

    private void increment(JsonObject object, String name) {
        int value = object.has(name) ? object.get(name).getAsInt() : 0;
        object.addProperty(name, value + 1);
    }

    private void copy(JsonObject source, JsonObject target, String name) {
        if (source.has(name)) {
            target.add(name, source.get(name).deepCopy());
        }
    }

    private boolean booleanValue(JsonObject object, String name) {
        return object != null && object.has(name) && !object.get(name).isJsonNull()
                && object.get(name).getAsBoolean();
    }

    private String selectedTargetId(JsonObject decision) {
        JsonObject plan = decision == null ? null : decision.getAsJsonObject("selectedPlan");
        return plan == null ? "" : stringValue(plan, "targetId");
    }

    private String planSummary(JsonObject decision) {
        JsonObject plan = decision == null ? null : decision.getAsJsonObject("selectedPlan");
        return plan == null ? "" : stringValue(plan, "summary");
    }

    private String stringValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()
                || !object.get(name).isJsonPrimitive()) {
            return "";
        }
        return object.get(name).getAsString();
    }

    private String limited(String text) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH) + "...";
    }
}
