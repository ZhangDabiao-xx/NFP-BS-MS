package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM 优化流程使用的确定性代码地图。
 *
 * <p>代码地图由程序从当前项目构建，不由模型生成。模型只能选择已登记的目标和
 * 修改点，文件路径、方法名与源码定位始终由本类和代码修改器负责。</p>
 */
final class ProjectCodeMap {

    /** 保存给用户查看的代码地图版本。 */
    static final String SCHEMA_VERSION = "1.0";

    private final Path projectRoot;
    private final Map<String, Target> targets;
    private final Map<String, EditPoint> editPoints;
    private final JsonObject mapJson;

    private ProjectCodeMap(Path projectRoot,
                           Map<String, Target> targets,
                           Map<String, EditPoint> editPoints,
                           JsonObject mapJson) {
        this.projectRoot = projectRoot;
        this.targets = targets;
        this.editPoints = editPoints;
        this.mapJson = mapJson;
    }

    /**
     * 构建并验证当前版本项目的优化代码地图。
     *
     * <p>新增可修改位置时，只需在此处登记目标、数据约束和两个唯一锚点；其余流程
     * 无需让模型猜测文件路径或方法名。</p>
     */
    static ProjectCodeMap create(Path projectRoot) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();

        Target beamRepack = new Target(
                "beam-repack-improve",
                "src/main/java/org/example/beamsearch/algo/BeamSearch.java",
                "BeamSearch.ImproveByRepack",
                "普通件和优先件共用的全局重排循环。",
                List.of(
                        "当前方法已有 MAX_NO_IMPROVEMENT_SWEEPS 无减板停止保护，不能重复添加。",
                        "不得修改时间上限、随机种子、multipSolve 或现有可行性校验。",
                        "当前候选对在 pairSet 中生成，pairRepackAttempts 的主要计算发生在后续 multipSolve 调用。"),
                List.of("beam-repack-pair-candidates"));

        EditPoint pairCandidates = new EditPoint(
                "beam-repack-pair-candidates",
                beamRepack.id(),
                beamRepack.file(),
                "            long pairGenerationStartNanos = System.nanoTime();",
                "            statistics.pairCandidatesGenerated += pairSet.size();",
                "replace_between",
                "替换 pairSet 候选生成区域。开始计时和结束统计锚点由程序保留，"
                        + "replacementText 只需在两者之间完成 pairSet 的构造。",
                List.of(
                        "这是二维排样：容器面积为 inst.length * inst.width。",
                        "Instance 只有 length 和 width 字段，不存在 height。",
                        "Box.volume 在本项目中表示矩形面积，可用于必要但非充分的面积上界过滤。",
                        "过滤条件只能跳过面积已超过目标板材总面积的候选，不能排除面积仍可能可行的候选。"));

        Map<String, Target> targets = Map.of(beamRepack.id(), beamRepack);
        Map<String, EditPoint> editPoints = Map.of(pairCandidates.id(), pairCandidates);
        verifyDataContracts(root);
        verifyTarget(root, beamRepack);
        verifyEditPoint(root, pairCandidates);

        JsonObject mapJson = buildMapJson(root, targets, editPoints);
        return new ProjectCodeMap(root, targets, editPoints, mapJson);
    }

    /** 返回可写入案例 llm 目录的完整、可审计代码地图。 */
    JsonObject asJson() {
        return mapJson.deepCopy();
    }

    /**
     * 返回给决策 Agent 的项目结构信息，不包含模型可修改的文件路径。
     */
    JsonObject decisionContext() {
        JsonObject context = new JsonObject();
        context.add("architecture", mapJson.get("architecture").deepCopy());
        context.add("moduleOverview", mapJson.get("moduleOverview").deepCopy());
        context.add("dataContracts", mapJson.get("dataContracts").deepCopy());

        JsonArray targetsJson = new JsonArray();
        for (Target target : targets.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("targetId", target.id());
            item.addProperty("purpose", target.purpose());
            JsonArray facts = new JsonArray();
            target.knownFacts().forEach(facts::add);
            item.add("knownFacts", facts);
            JsonArray pointIds = new JsonArray();
            target.editPointIds().forEach(pointIds::add);
            item.add("permittedEditPointIds", pointIds);
            targetsJson.add(item);
        }
        context.add("optimizationTargets", targetsJson);
        return context;
    }

    /** 读取所有可优化方法的真实源码，供决策 Agent 理解实际实现。 */
    String readDecisionSourceContext(SourceContextReader reader) throws IOException {
        StringBuilder context = new StringBuilder();
        for (Target target : targets.values()) {
            context.append(reader.readForTarget(target.file(), target.method()));
        }
        return context.toString();
    }

    /**
     * 为代码修改 Agent 构造与本次决策对应的受限上下文。
     */
    JsonObject implementationContext(JsonObject decision,
                                     SourceContextReader reader) throws IOException {
        Target target = selectedTarget(decision);
        JsonObject context = new JsonObject();
        context.add("dataContracts", mapJson.get("dataContracts").deepCopy());

        JsonObject selectedTarget = new JsonObject();
        selectedTarget.addProperty("targetId", target.id());
        selectedTarget.addProperty("purpose", target.purpose());
        JsonArray facts = new JsonArray();
        target.knownFacts().forEach(facts::add);
        selectedTarget.add("knownFacts", facts);
        selectedTarget.addProperty("sourceContext", reader.readForTarget(target.file(), target.method()));
        context.add("selectedTarget", selectedTarget);

        JsonArray points = new JsonArray();
        for (String pointId : target.editPointIds()) {
            EditPoint point = editPoints.get(pointId);
            JsonObject item = new JsonObject();
            item.addProperty("editPointId", point.id());
            item.addProperty("operation", point.operation());
            item.addProperty("instruction", point.instruction());
            JsonArray constraints = new JsonArray();
            point.constraints().forEach(constraints::add);
            item.add("constraints", constraints);
            item.addProperty("editableSource", editableSource(point));
            points.add(item);
        }
        context.add("editPoints", points);
        return context;
    }

    /** 决策阶段只接受预登记的 targetId。 */
    void validateDecision(JsonObject decision) {
        if (!"ready_for_implementation".equals(stringValue(decision, "status"))) {
            return;
        }
        JsonObject plan = decision.getAsJsonObject("selectedPlan");
        String targetId = stringValue(plan, "targetId");
        if (!targets.containsKey(targetId)) {
            throw new IllegalArgumentException("Decision Agent 返回了未登记的 targetId: " + targetId);
        }
    }

    /** 代码修改阶段只接受当前 targetId 下预登记的 editPointId。 */
    void validateChangeSet(JsonObject decision, JsonObject changeSet) {
        Target target = selectedTarget(decision);
        JsonArray changes = changeSet.getAsJsonArray("changes");
        if (changes == null) {
            throw new IllegalArgumentException("代码修改结果缺少 changes 数组。");
        }

        Set<String> usedPoints = new LinkedHashSet<>();
        for (JsonElement element : changes) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("changes 中的每项必须是对象。");
            }
            String pointId = stringValue(element.getAsJsonObject(), "editPointId");
            if (!target.editPointIds().contains(pointId)) {
                throw new IllegalArgumentException("修改点不属于当前 targetId: " + pointId);
            }
            if (!usedPoints.add(pointId)) {
                throw new IllegalArgumentException("同一 editPointId 不能在一轮中重复修改: " + pointId);
            }
        }
    }

    /** 由程序把 targetId 解析成真实目标；模型永远不需要提供文件名。 */
    Target selectedTarget(JsonObject decision) {
        JsonObject plan = decision.getAsJsonObject("selectedPlan");
        String targetId = stringValue(plan, "targetId");
        Target target = targets.get(targetId);
        if (target == null) {
            throw new IllegalArgumentException("决策没有有效 targetId: " + targetId);
        }
        return target;
    }

    /** 由程序把 editPointId 解析成真实锚点与文件。 */
    EditPoint editPoint(JsonObject decision, String pointId) {
        Target target = selectedTarget(decision);
        EditPoint point = editPoints.get(pointId);
        if (point == null || !target.editPointIds().contains(pointId)) {
            throw new IllegalArgumentException("决策目标不允许使用修改点: " + pointId);
        }
        return point;
    }

    Path sourceFile(EditPoint point) {
        return projectRoot.resolve(point.file()).normalize();
    }

    private String editableSource(EditPoint point) throws IOException {
        String source = Files.readString(sourceFile(point), StandardCharsets.UTF_8);
        int start = uniqueIndex(source, point.startAnchor(), "开始锚点");
        int end = uniqueIndex(source, point.endAnchor(), "结束锚点");
        if (end <= start) {
            throw new IllegalStateException("修改点锚点顺序错误: " + point.id());
        }
        return source.substring(afterLineBreak(source, start + point.startAnchor().length()), end);
    }

    private static void verifyTarget(Path root, Target target) throws IOException {
        Path file = root.resolve(target.file()).normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("代码地图目标文件不存在: " + file);
        }
        String methodName = target.method().substring(target.method().lastIndexOf('.') + 1);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (!source.contains(methodName + "(")) {
            throw new IllegalStateException("代码地图目标方法不存在: " + target.method());
        }
    }

    /** 防止项目数据模型变化后，旧代码地图向模型提供错误约束。 */
    private static void verifyDataContracts(Path root) throws IOException {
        String instance = Files.readString(root.resolve(
                "src/main/java/org/example/beamsearch/common/Instance.java"), StandardCharsets.UTF_8);
        String box = Files.readString(root.resolve(
                "src/main/java/org/example/beamsearch/common/Box.java"), StandardCharsets.UTF_8);
        if (!instance.contains("public int length;") || !instance.contains("public int width;")
                || !box.contains("public double volume;")) {
            throw new IllegalStateException("代码地图的数据约束与当前 Instance 或 Box 定义不一致。");
        }
    }

    private static void verifyEditPoint(Path root, EditPoint point) throws IOException {
        Path file = root.resolve(point.file()).normalize();
        String source = Files.readString(file, StandardCharsets.UTF_8);
        int start = uniqueIndex(source, point.startAnchor(), "开始锚点");
        int end = uniqueIndex(source, point.endAnchor(), "结束锚点");
        if (end <= start) {
            throw new IllegalStateException("代码地图修改点锚点顺序错误: " + point.id());
        }
    }

    private static int uniqueIndex(String source, String anchor, String name) {
        int first = source.indexOf(anchor);
        if (first < 0) {
            throw new IllegalStateException(name + "未在当前源码中匹配。");
        }
        if (source.indexOf(anchor, first + 1) >= 0) {
            throw new IllegalStateException(name + "在当前源码中不唯一。");
        }
        return first;
    }

    /** 跳过开始锚点所在行的换行符，使模型无需复写该锚点。 */
    private static int afterLineBreak(String source, int index) {
        if (index < source.length() && source.charAt(index) == '\r') {
            index++;
        }
        if (index < source.length() && source.charAt(index) == '\n') {
            index++;
        }
        return index;
    }

    /** 扫描源码目录，生成项目模块概览；它仅用于帮助模型理解模块职责。 */
    private static JsonArray moduleOverview(Path root) throws IOException {
        Path sourceRoot = root.resolve("src/main/java");
        Map<String, List<String>> classesByModule = new LinkedHashMap<>();
        try (var paths = Files.walk(sourceRoot)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        Path relative = sourceRoot.relativize(path);
                        String module = moduleName(relative);
                        String className = path.getFileName().toString().replace(".java", "");
                        classesByModule.computeIfAbsent(module, ignored -> new ArrayList<>()).add(className);
                    });
        }

        JsonArray result = new JsonArray();
        for (Map.Entry<String, List<String>> entry : classesByModule.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("module", entry.getKey());
            item.addProperty("classCount", entry.getValue().size());
            JsonArray classes = new JsonArray();
            entry.getValue().forEach(classes::add);
            item.add("classes", classes);
            result.add(item);
        }
        return result;
    }

    private static String moduleName(Path relative) {
        int count = relative.getNameCount();
        if (count <= 1) {
            return "root";
        }
        if ("org".equals(relative.getName(0).toString()) && count >= 3) {
            return relative.getName(1) + "/" + relative.getName(2);
        }
        return relative.getName(0).toString();
    }

    private static JsonObject buildMapJson(Path root,
                                           Map<String, Target> targets,
                                           Map<String, EditPoint> editPoints) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", SCHEMA_VERSION);
        result.addProperty("sourceRoot", "src/main/java");

        JsonArray architecture = new JsonArray();
        architecture.add("运行入口：LoadingTestRun.runWithImprove。");
        architecture.add("排样调度：PriorityFirstPacker 调用 GlobalRepackOptimizer.optimize。");
        architecture.add("全局重排：GlobalRepackOptimizer.optimize 调用 BeamSearch.ImproveByRepack。");
        architecture.add("局部求解：BeamSearch.ImproveByRepack 对候选 pairSet 调用 multipSolve。");
        result.add("architecture", architecture);
        result.add("moduleOverview", moduleOverview(root));

        JsonArray contracts = new JsonArray();
        contracts.add("项目是二维排样；Instance 的容器尺寸字段为 length 与 width。");
        contracts.add("Box.volume 是外接矩形面积；容器面积为 Instance.length * Instance.width。");
        contracts.add("RunReportWriter 只读写运行统计，不参与 Beam Search 搜索决策。");
        result.add("dataContracts", contracts);

        JsonArray targetArray = new JsonArray();
        for (Target target : targets.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("targetId", target.id());
            item.addProperty("file", target.file());
            item.addProperty("method", target.method());
            item.addProperty("purpose", target.purpose());
            JsonArray facts = new JsonArray();
            target.knownFacts().forEach(facts::add);
            item.add("knownFacts", facts);
            JsonArray pointIds = new JsonArray();
            target.editPointIds().forEach(pointIds::add);
            item.add("editPointIds", pointIds);
            targetArray.add(item);
        }
        result.add("optimizationTargets", targetArray);

        JsonArray pointArray = new JsonArray();
        for (EditPoint point : editPoints.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("editPointId", point.id());
            item.addProperty("targetId", point.targetId());
            item.addProperty("operation", point.operation());
            item.addProperty("instruction", point.instruction());
            JsonArray constraints = new JsonArray();
            point.constraints().forEach(constraints::add);
            item.add("constraints", constraints);
            pointArray.add(item);
        }
        result.add("editPoints", pointArray);
        return result;
    }

    private static String stringValue(JsonObject object, String name) {
        if (object == null) {
            return "";
        }
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? "" : value.getAsString();
    }

    record Target(String id,
                  String file,
                  String method,
                  String purpose,
                  List<String> knownFacts,
                  List<String> editPointIds) {
    }

    record EditPoint(String id,
                     String targetId,
                     String file,
                     String startAnchor,
                     String endAnchor,
                     String operation,
                     String instruction,
                     List<String> constraints) {
    }
}
