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
    static final String SCHEMA_VERSION = "2.0";

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

        Target spaceOrdering = new Target(
                "space-ordering",
                "src/main/java/org/example/beamsearch/common/SpaceComparator.java",
                "SpaceComparator.compare",
                "初始解和局部搜索中选择下一个空闲空间的比较规则。",
                List.of(
                        "SpaceManager.chooseBestSpace 通过此 Comparator 在所有空闲空间中选最优空间。",
                        "当前规则优先角点距离，再优先空闲面积。",
                        "Comparator 必须保持确定性、传递性，且相同空间必须返回 0。"),
                List.of("space-comparator-order"),
                List.of(
                        "src/main/java/org/example/beamsearch/common/Space.java",
                        "src/main/java/org/example/beamsearch/common/Instance.java"));

        Target blockOrdering = new Target(
                "block-ordering",
                "src/main/java/org/example/beamsearch/state/State.java",
                "State.chooseBestBlocks",
                "给定空闲空间后，选择进入 Beam Search 扩展的可行组块顺序。",
                List.of(
                        "当前方法按 availableBlocks 的既有顺序返回前 count 个可行组块。",
                        "组块顺序会影响初始解与后续 Beam Search 分支的质量。",
                        "不得修改 freeBoxes、空间切分、组块可行性判断或 State 的不可变复制语义。"),
                List.of("state-feasible-block-order"),
                List.of(
                        "src/main/java/org/example/beamsearch/blockgenerator/GeneralBlock.java",
                        "src/main/java/org/example/beamsearch/common/Space.java"));

        Target repackBlockOrdering = new Target(
                "repack-block-ordering",
                "src/main/java/org/example/beamsearch/algo/BeamSearch.java",
                "BeamSearch.multipSolve",
                "拆板重组时，局部多板 Beam Search 使用的组块排序规则。",
                List.of(
                        "当前 multipSolve 按 boxVolume、length 排序 allBlocks，并以固定宽度扩展候选节点。",
                        "此目标直接影响重组后能否减少 So，但不允许改变 multipSolve 的容器数量语义。",
                        "不得修改 WIDTH、容器数量、可行性检查或未排工件统计。"),
                List.of("multip-solve-block-order"),
                List.of("src/main/java/org/example/beamsearch/blockgenerator/GeneralBlock.java"));

        Target beamRepack = new Target(
                "beam-repack-improve",
                "src/main/java/org/example/beamsearch/algo/BeamSearch.java",
                "BeamSearch.ImproveByRepack",
                "普通件和优先件共用的全局重排循环，包括待拆板选择、候选对顺序与停止条件。",
                List.of(
                        "当前方法已有 MAX_NO_IMPROVEMENT_SWEEPS 无减板停止保护，不能重复添加。",
                        "纯矩形使用整案例下界，混排分别使用优先矩形与插入后剩余普通矩形的逐件旋转下界；必须保留优化前及实际减板后的下界停止。",
                        "初始至少十板且连续两轮未减板时加入三板候选，减板后恢复一两板；部分搬运不重置无减板轮数。",
                        "不得增加时间上限、改变问题约束、删除可行性校验或修改 multipSolve 的调用语义。",
                        "必须保留残余单板重建和完整布局校验后才提交的事务边界，不得改写邻域扩张阈值。",
                        "当前候选对在 pairSet 中生成，pairRepackAttempts 的主要计算发生在后续 multipSolve 调用。",
                        "优先目标是提高 Sp、So、N 和利用率；时间只在解质量不下降时作为次级目标。"),
                List.of(
                        "repack-board-selection",
                        "beam-repack-pair-candidates",
                        "repack-candidate-order",
                        "repack-stop-condition"),
                List.of(
                        "src/main/java/org/example/beamsearch/common/Solution.java",
                        "src/main/java/org/example/beamsearch/common/Box.java",
                        "src/main/java/org/example/beamsearch/common/Instance.java"));

        EditPoint spaceComparatorOrder = new EditPoint(
                "space-comparator-order",
                spaceOrdering.id(),
                spaceOrdering.file(),
                "    @Override",
                "    public static Comparator<Space> getSpaceComparator",
                "replace_between",
                "替换 SpaceComparator.compare 方法体。保留 @Override 与工厂方法锚点。",
                List.of(
                        "只能调整空闲空间的排序评分和确定性 tie-break，不能改变 Space 几何或删除空间。",
                        "比较器必须满足 Comparator 契约；不得使用随机数、全局状态或时间。",
                        "可使用角点距离、空闲面积、长宽比等已存在的 Space 信息。"));

        EditPoint feasibleBlockOrder = new EditPoint(
                "state-feasible-block-order",
                blockOrdering.id(),
                blockOrdering.file(),
                "    public ArrayList<GeneralBlock> chooseBestBlocks(Space s, int count) {",
                "    public double getScoreVolume() {",
                "replace_between",
                "替换 chooseBestBlocks 的方法体，设计给定空间内的可行组块选择顺序。",
                List.of(
                        "返回结果不得超过 count，且只能包含 length、width 均可放入空间的组块。",
                        "不得修改 availableBlocks 或 freeBoxes；方法只能创建局部候选列表。",
                        "优先依据空间填充率、形状匹配和组块 scoreVolume 等真实字段设计启发式。"));

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
                        "Box.volume 在本项目中表示矩形面积；候选同时支持减板和部分搬运。",
                        "不得因移出板与接收板工件总面积超过接收板总容量而过滤候选，残余可放回移出板的部分搬运仍可能有效。"));

        EditPoint multipSolveBlockOrder = new EditPoint(
                "multip-solve-block-order",
                repackBlockOrdering.id(),
                repackBlockOrdering.file(),
                "        Arrays.sort(allBlocks, (o1, o2) -> {",
                "        int WIDTH = 10;",
                "replace_between",
                "替换拆板重组 allBlocks 的排序区域；结束锚点后的 Beam 宽度和搜索流程由程序保留。",
                List.of(
                        "只能改变 allBlocks 排序，不能删除组块、改变 containerNum 或调用随机数。",
                        "排序必须提供稳定的 tie-break，避免 Comparator 对不同组块返回不一致结果。",
                        "可依据 boxVolume、blockVolume、scoreVolume、长度和宽度设计重组优先级。"));

        EditPoint repackBoardSelection = new EditPoint(
                "repack-board-selection",
                beamRepack.id(),
                beamRepack.file(),
                "            System.out.println(\"Selecting boards with low utilization for repacking...\");",
                "            if (location == -1 || executionResult.solutions.get(location).getUtilization() > allAvgUtilization) {",
                "replace_between",
                "替换待拆板选择区域；结束锚点后的低利用率保护由程序保留。",
                List.of(
                        "必须为 location 赋予有效板材下标，或在没有候选时保留 -1。",
                        "不得修改 executionResult.solutions，不得提前删除板材。",
                        "可依据利用率、空闲面积、已放工件数等 Solution 的真实信息评分。"));

        EditPoint repackCandidateOrder = new EditPoint(
                "repack-candidate-order",
                beamRepack.id(),
                beamRepack.file(),
                "            // 修改原因：当前板材已经计入 locations，候选为空时只应跳过",
                "            int iteration = 0;",
                "replace_between",
                "替换 pairSet 为空处理与候选遍历顺序区域。",
                List.of(
                        "pairSet 为空时必须继续外层循环，不能访问 pairSet 下标。",
                        "可以直接排序或保留 shuffle，但不得通过 AbstractList、no-op set 或其他方式破坏 Java 集合契约。",
                        "不得删除 pairSet 元素；排序只影响尝试顺序。"));

        EditPoint repackStopCondition = new EditPoint(
                "repack-stop-condition",
                beamRepack.id(),
                beamRepack.file(),
                "            // 双重停止条件：连续完成多轮完整扫描且没有减少板材数量时，",
                "            // 修改原因：减板后 solutions 数量会变化，不能继续使用初始板材数。",
                "replace_between",
                "替换全局重排的无改进停止条件区域。",
                List.of(
                        "不得增加 maxTime 或跳过最前面的时间上限检查。",
                        "停止条件必须由已有统计或当前循环状态决定，不能使用随机数。",
                        "只有证据表明无收益扫描过多时才应收紧停止策略；不能简单减少搜索轮数。"));

        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put(spaceOrdering.id(), spaceOrdering);
        targets.put(blockOrdering.id(), blockOrdering);
        targets.put(repackBlockOrdering.id(), repackBlockOrdering);
        targets.put(beamRepack.id(), beamRepack);

        Map<String, EditPoint> editPoints = new LinkedHashMap<>();
        editPoints.put(spaceComparatorOrder.id(), spaceComparatorOrder);
        editPoints.put(feasibleBlockOrder.id(), feasibleBlockOrder);
        editPoints.put(multipSolveBlockOrder.id(), multipSolveBlockOrder);
        editPoints.put(repackBoardSelection.id(), repackBoardSelection);
        editPoints.put(pairCandidates.id(), pairCandidates);
        editPoints.put(repackCandidateOrder.id(), repackCandidateOrder);
        editPoints.put(repackStopCondition.id(), repackStopCondition);
        verifyDataContracts(root);
        for (Target target : targets.values()) {
            verifyTarget(root, target);
        }
        for (EditPoint point : editPoints.values()) {
            verifyEditPoint(root, point);
        }

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
        if (!target.relatedContextFiles().isEmpty()) {
            selectedTarget.addProperty("relatedSourceContext",
                    reader.readForFiles(target.relatedContextFiles()));
        }
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
        architecture.add("初始解策略：SpaceManager 通过 SpaceComparator 选择空间，State 选择可行 GeneralBlock。");
        result.add("architecture", architecture);
        result.add("moduleOverview", moduleOverview(root));

        JsonArray contracts = new JsonArray();
        contracts.add("项目是二维排样；Instance 的容器尺寸字段为 length 与 width。");
        contracts.add("Box.volume 是外接矩形面积；容器面积为 Instance.length * Instance.width。");
        contracts.add("RunReportWriter 只读写运行统计，不参与 Beam Search 搜索决策。");
        contracts.add("优化目标按字典序比较：Sp、So、N、平均利用率、总排样时间。");
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
            JsonArray relatedFiles = new JsonArray();
            target.relatedContextFiles().forEach(relatedFiles::add);
            item.add("relatedContextFiles", relatedFiles);
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
                  List<String> editPointIds,
                  List<String> relatedContextFiles) {
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
