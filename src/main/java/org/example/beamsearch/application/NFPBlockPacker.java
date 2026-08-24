package org.example.beamsearch.application;

import org.example.beamsearch.algo.BeamSearch;
import org.example.beamsearch.common.*;
import org.example.beamsearch.spacemanager.SpaceManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NFP 组块 → BeamSearch 排样的桥接主程序。
 *
 * 数据流：
 *   1. 从 data/outputData/*.txt 读取 NFP 拼接结果（block 列表）
 *   2. 从 data/inputData/*.json 读取板材尺寸（plates）
 *   3. 将每个 block 的外接矩形映射为一个 Box
 *   4. 按 BackFrontPriority 分组，每组对应一个 BeamSearch Instance
 *   5. 对每个 Instance 执行 BeamSearch 排样求解
 *   6. 输出排样结果到 data/packResult/
 */
public class NFPBlockPacker {

    private static final Path DEFAULT_OUTPUT_DIR = Path.of("data", "outputData");
    private static final Path DEFAULT_INPUT_DIR  = Path.of("data", "inputData");
    private static final Path DEFAULT_RESULT_DIR = Path.of("data", "packResult");

    private static final int BEAM_SEARCH_TIME_MS  = 30_000;  // 单个 Instance 求解时限（毫秒）
    private static final int BEAM_CNT_NUM         = 1000;

    // ---------- 内部数据模型 ----------

    /** 从 outputData 解析出的单个子物品位置；placedPoints 是第一阶段拼接后的真实顶点 */
    private static class ItemRecord {
        final List<double[]> placedPoints;

        ItemRecord(List<double[]> placedPoints) {
            this.placedPoints = placedPoints;
        }
    }

    /** 从 outputData 解析出的单个 block 摘要信息 */
    private static class BlockRecord {
        final String id;
        final boolean backFrontPriority;
        final List<Integer> rotate;
        final double boxArea;
        /** outline 多边形顶点（已归一化到原点） */
        final List<double[]> outline;
        /** block 内所有子物品的拼接后顶点，用于第二阶段矩形化 */
        final List<ItemRecord> items;

        BlockRecord(String id, boolean backFrontPriority, List<Integer> rotate,
                    double boxArea, List<double[]> outline, List<ItemRecord> items) {
            this.id = id;
            this.backFrontPriority = backFrontPriority;
            this.rotate = rotate;
            this.boxArea = boxArea;
            this.outline = outline;
            this.items = items;
        }
    }

    // ---------- 入口 ----------

    public static void main(String[] args) throws IOException {
        Path outputDir = args.length > 0 ? Path.of(args[0]) : DEFAULT_OUTPUT_DIR;
        Path inputDir  = args.length > 1 ? Path.of(args[1]) : DEFAULT_INPUT_DIR;
        Path resultDir = args.length > 2 ? Path.of(args[2]) : DEFAULT_RESULT_DIR;
        processAllCases(outputDir, inputDir, resultDir);
    }

    // ---------- 批处理 ----------

    /**
     * 扫描 outputData 目录下所有 .txt 文件，逐一处理。
     * 每个 .txt 文件需要对应 inputData 中同名 .json 来获取板材尺寸。
     */
    public static void processAllCases(Path outputDir, Path inputDir, Path resultDir) throws IOException {
        Files.createDirectories(resultDir);

        List<Path> txtFiles;
        try (var stream = Files.list(outputDir)) {
            txtFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }

        for (Path txtFile : txtFiles) {
            String caseName = stripExtension(txtFile.getFileName().toString());
            Path jsonFile = inputDir.resolve(caseName + ".json");

            if (!Files.exists(jsonFile)) {
                System.out.println("[跳过] 找不到对应 JSON: " + jsonFile);
                continue;
            }

            System.out.println("处理案例: " + caseName);

            // 1. 读取 outputData 中的 block 列表
            List<BlockRecord> blocks = parseOutputData(txtFile);
            if (blocks.isEmpty()) {
                System.out.println("  无 block，跳过");
                continue;
            }

            // 2. 从 JSON 读取板材尺寸
            Container plate = readPlateFromJson(jsonFile);
            System.out.printf("  板材: %d x %d (scaled), blocks: %d%n",
                    plate.length, plate.width, blocks.size());

            // 3. block → Box 转换
            List<Box> allBoxes = blocksToBoxes(blocks);

            // 4. 按 BackFrontPriority 分组，每组构建 Instance
            Map<String, List<Box>> grouped = groupByPriority(allBoxes);
            List<Instance> instances = new ArrayList<>();
            for (Map.Entry<String, List<Box>> entry : grouped.entrySet()) {
                Container container = new Container(entry.getKey(),
                        plate.length / 10.0,
                        plate.width / 10.0,
                        0);
                instances.add(new Instance(new ArrayList<>(entry.getValue()), container));
            }

            // 5. 对每个 Instance 执行排样
            Path caseResultDir = resultDir.resolve(caseName);
            Files.createDirectories(caseResultDir);
            for (int i = 0; i < instances.size(); i++) {
                Instance inst = instances.get(i);
                System.out.printf("  排样 group %d/%d (color=%s, boxes=%d)%n",
                        i + 1, instances.size(),
                        inst.boxes.length > 0 ? inst.boxes[0].color : "?",
                        inst.totalBoxCount);

                ExecutionResult result = packInstance(inst, BEAM_SEARCH_TIME_MS, BEAM_CNT_NUM);
                writePackResult(caseResultDir, i + 1, inst, result);
            }

            System.out.println("  完成: " + caseResultDir);
        }
    }

    // ---------- 解析 outputData ----------

    /**
     * 解析 NFP 拼接输出的文本文件，提取每个 block 的关键字段。
     *
     * 文件格式示例:
     *   blockCount=517
     *
     *   block 1
     *   id=28239641061
     *   BackFrontPriority=false
     *   rotate=[0,90]
     *   score2=0.000000
     *   boxArea=189104.000000
     *   outline=[[848.0,0.0],[848.0,223.0],[0.0,223.0],[0.0,0.0]]
     *   combinedCoordinates=...
     *   items=
     *     ...
     */
    static List<BlockRecord> parseOutputData(Path file) throws IOException {
        List<BlockRecord> blocks = new ArrayList<>();
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = text.split("\\R");

        int blockCount = 0;
        // 第一行: blockCount=N
        if (lines.length > 0 && lines[0].startsWith("blockCount=")) {
            blockCount = Integer.parseInt(lines[0].substring("blockCount=".length()));
        }

        int i = 0;
        while (i < lines.length) {
            // 跳过空行，寻找 "block N" 行
            if (!lines[i].startsWith("block ")) {
                i++;
                continue;
            }

            String id = "";
            boolean priority = false;
            List<Integer> rotate = List.of(0);
            double boxArea = 0;
            List<double[]> outline = List.of();
            List<ItemRecord> items = new ArrayList<>();
            i++;

            // 读取 block 内的键值对，直到下一个 block 或文件末尾
            while (i < lines.length && !lines[i].startsWith("block ")) {
                String line = lines[i].trim();
                if (line.isEmpty()) { i++; continue; }

                if (line.startsWith("id=") && !line.startsWith("- id=")) {
                    id = line.substring("id=".length());
                } else if (line.startsWith("BackFrontPriority=")) {
                    priority = Boolean.parseBoolean(line.substring("BackFrontPriority=".length()));
                } else if (line.startsWith("rotate=")) {
                    rotate = parseIntList(line.substring("rotate=".length()));
                } else if (line.startsWith("boxArea=")) {
                    boxArea = Double.parseDouble(line.substring("boxArea=".length()));
                } else if (line.startsWith("outline=")) {
                    outline = parsePointList(line.substring("outline=".length()));
                }
                if (line.equals("items=") || line.startsWith("items=")) {
                    i++;
                    while (i < lines.length && !lines[i].startsWith("block ")) {
                        String rawLine = lines[i];
                        String itemLine = rawLine.trim();
                        if (itemLine.isEmpty()) { i++; continue; }
                        if (!rawLine.startsWith("  -") && !rawLine.startsWith("    ")) break;
                        if (itemLine.startsWith("placedPoints=")) {
                            // 第二阶段矩形化只需要拼接后的顶点；originalPoints 仍是单件初始坐标，不能用于组合块 bbox。
                            items.add(new ItemRecord(parsePointList(itemLine.substring("placedPoints=".length()))));
                        }
                        i++;
                    }
                    continue;
                }
                i++;
            }

            if (!id.isEmpty()) {
                blocks.add(new BlockRecord(id, priority, rotate, boxArea, outline, items));
            }
        }

        System.out.printf("  解析完成: 预期 %d 个 block，实际 %d 个%n", blockCount, blocks.size());
        return blocks;
    }

    /** 解析 Java int list 格式: "[0,90]" → List.of(0, 90) */
    private static List<Integer> parseIntList(String raw) {
        raw = raw.trim();
        if (raw.equals("[]")) return List.of(0);
        // 去掉方括号
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .toList();
    }

    /** 解析坐标点列表: "[[848.0,0.0],[848.0,223.0],...]" */
    private static List<double[]> parsePointList(String raw) {
        raw = raw.trim();
        List<double[]> points = new ArrayList<>();
        // 匹配每个 [x,y] 子数组
        Matcher m = Pattern.compile("\\[\\s*([\\d.\\-]+)\\s*,\\s*([\\d.\\-]+)\\s*]").matcher(raw);
        while (m.find()) {
            points.add(new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))});
        }
        return points;
    }

    // ---------- 读取板材 ----------

    /**
     * 从 inputData JSON 中读取 plates 数组的第一块板材尺寸。
     *
     * JSON 中 plates 字段示例:
     *   "plates": [{"height": 1220, "width": 2440, ...}]
     *
     * 返回的 Container 内部会自动将长宽 ×10 存储为 int，供 BeamSearch 使用。
     */
    static Container readPlateFromJson(Path jsonFile) throws IOException {
        String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray plates = root.getAsJsonArray("plates");

        double width  = 2440;  // 默认值
        double height = 1220;

        if (plates != null && plates.size() > 0) {
            JsonObject first = plates.get(0).getAsJsonObject();
            JsonElement w = first.get("width");
            JsonElement h = first.get("height");
            if (w != null) width  = w.getAsDouble();
            if (h != null) height = h.getAsDouble();
        }

        // Container 内部: length = 10*width, width = 10*height, orientId=0 允许旋转
        return new Container("0", width, height, 0);
    }

    // ---------- Block → Box 映射 ----------

    /**
     * 将 NFP 输出的 block 列表逐一转换为 BeamSearch 的 Box 对象。
     *
     * 每个 block 的 bounding box（外接矩形）直接作为排样矩形:
     *   - length = 拼接后全部 placedPoints 的最大 x - 最小 x
     *   - width  = 拼接后全部 placedPoints 的最大 y - 最小 y
     *
     * Box 的旋转能力由 block 原始 rotate 列表决定:
     *   - 包含 90 度 → orientId=2（可旋转 90°）
     *   - 否则       → orientId=0（不可旋转）
     */
    static List<Box> blocksToBoxes(List<BlockRecord> blocks) {
        List<Box> boxes = new ArrayList<>(blocks.size());
        int typeNum = 1;
        for (BlockRecord block : blocks) {
            Box box = new Box();
            box.typeNum = typeNum++;
            box.count   = 1;
            box.name    = block.id;
            box.id      = block.id;
            box.color   = block.backFrontPriority ? "1" : "0";

            // 使用拼接后的 placedPoints 计算外接矩形，避免 outline 只覆盖最大轮廓时压缩排样尺寸。
            double[] bbox = computeBBox(blockPlacedPoints(block));
            box.length = bbox[2] - bbox[0];  // maxX - minX
            box.width  = bbox[3] - bbox[1];  // maxY - minY
            box.volume = box.length * box.width;
            box.scoreVolume = box.volume;
            box.sizeVolume   = box.volume;

            // 缩放尺寸（BeamSearch 统一使用 ×10 的整数坐标）
            int sl = (int) (10 * box.length);
            int sw = (int) (10 * box.width);
            box.size = new int[]{sl, sw};

            // 旋转能力：90° 或 270° 都表示矩形排样时可以交换长宽方向。
            boolean rotatable = canRotateInBeamSearch(block.rotate);
            if (rotatable) {
                box.orientId = 2;
                box.variation = new int[][]{
                        {sw, sl},   // 方向 0: width × length
                        {sl, sw}    // 方向 1: length × width（旋转 90°）
                };
            } else {
                box.orientId = 0;
                box.variation = new int[][]{
                        {sw, sl}    // 仅一个方向
                };
            }
            box.containerOrientId = 0;

            // 排样结果追踪用: 两个 orientation 各一个 id 队列
            box.ids = new ArrayList<>(Arrays.asList(
                    new LinkedList<>(List.of(block.id)),
                    new LinkedList<>(List.of(block.id))
            ));

            boxes.add(box);
        }
        return boxes;
    }

    /** 计算多边形顶点列表的 bounding box: [minX, minY, maxX, maxY] */
    private static double[] computeBBox(List<double[]> polygon) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : polygon) {
            if (p[0] < minX) minX = p[0];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] < minY) minY = p[1];
            if (p[1] > maxY) maxY = p[1];
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    private static List<double[]> blockPlacedPoints(BlockRecord block) {
        // placedPoints 是第一阶段拼接后的真实顶点集合；第二阶段矩形化不能使用 originalPoints。
        List<double[]> points = new ArrayList<>();
        for (ItemRecord item : block.items) {
            points.addAll(item.placedPoints);
        }
        if (!points.isEmpty()) {
            return points;
        }
        // 兼容没有 items 段的历史输出；这种旧数据无法恢复子物品拼接后顶点，只能退回 outline。
        return block.outline;
    }

    private static boolean canRotateInBeamSearch(List<Integer> rotations) {
        // BeamSearch 只区分不旋转与交换长宽；允许 90° 或 270° 都应视为可旋转。
        return rotations.contains(90) || rotations.contains(270);
    }

    // ---------- 按优先级分组 ----------

    /**
     * 按 BackFrontPriority 分组，相同优先级的 Box 放入同一组。
     * BeamSearch 内部按 color 字段区分不同的 Instance。
     */
    static Map<String, List<Box>> groupByPriority(List<Box> boxes) {
        Map<String, List<Box>> map = new LinkedHashMap<>();
        for (Box box : boxes) {
            map.computeIfAbsent(box.color, k -> new ArrayList<>()).add(box);
        }
        return map;
    }

    // ---------- 排样求解 ----------

    /**
     * 对单个 Instance 调用 BeamSearch 执行排样。
     * 参数含义与 LoadingTestRun.solve() 保持一致。
     */
    static ExecutionResult packInstance(Instance instance, int timeMs, int cntNum) {
        int minCon = (int) (instance.totalBoxVolume
                / ((long) instance.length * instance.width));
        // 确保 searchTime 合理
        int searchTime = timeMs;
        if (minCon > 0 && minCon > searchTime / 2) {
            searchTime = (int) (((double) searchTime / (2.0 * minCon)) * 1000);
        }

        Comparator<Space> spaceComparator = SpaceComparator.getSpaceComparator(instance, cntNum);
        SpaceManager spaceManager = new SpaceManager(spaceComparator);
        BeamSearch beamSearch = new BeamSearch(spaceManager, instance);
        ExecutionResult result = beamSearch.solve(searchTime, minCon);
        result.setAvgUtilization();
        System.out.printf("    结果: %d 张板, 利用率 %.2f%%%n",
                result.solutions.size(), result.avgUtilization);
        return result;
    }

    // ---------- 结果输出 ----------

    /**
     * 将排样结果写入 CSV 文件，格式与 beamsearch 原生输出对齐：
     *   BatchNo, BoardNo, Color, UPI, X, Y, R, L, W, Area, Type, SubCode
     */
    static void writePackResult(Path resultDir, int groupIndex, Instance inst,
                                ExecutionResult result) throws IOException {
        String csvFile = resultDir.resolve("pack_group" + groupIndex + ".csv").toString();
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(csvFile), StandardCharsets.UTF_8)))) {
            pw.println("BatchNo,BoardNo,Color,UPI,X,Y,R,L,W,Area,Type,SubCode");

            int containerCount = result.solutions.size();
            for (int boardIdx = 0; boardIdx < containerCount; boardIdx++) {
                Solution sol = result.solutions.get(boardIdx);
                for (PlacedCuboid p : sol.getPlacedCuboid()) {
                    // orient: 0=原始方向, 90=旋转后
                    int orient = 90;
                    double rawL = p.box.length;
                    double rawW = p.box.width;
                    if (p.length == p.box.size[0] && p.width == p.box.size[1]) {
                        orient = 0;
                    }

                    String id = p.box.ids.get(0).poll();
                    if (id == null) {
                        id = p.box.ids.get(1).poll();
                        if (id != null && orient == 0) orient = 90;
                    }
                    if (id == null) id = "?";

                    String color = p.box.color;
                    if (color == null) color = "0";

                    double area = p.box.volume;
                    double placedL = p.length / 10.0;
                    double placedW = p.width  / 10.0;
                    double placedX = p.x / 10.0;
                    double placedY = p.y / 10.0;

                    pw.printf(Locale.ROOT, "%s,%d,%s,%s,%.1f,%.1f,%d,%.1f,%.1f,%.2f,%s,%s%n",
                            "nfp_blocks", boardIdx + 1, color, id,
                            placedX, placedY, orient,
                            placedL, placedW, area,
                            "NFP", "");
                }
            }
        }

        // 同时输出统计
        String statFile = resultDir.resolve("pack_group" + groupIndex + "_statistics.csv").toString();
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(statFile), StandardCharsets.UTF_8)))) {
            pw.println("Group,Boards,Boxes,AvgUtilization");
            int totalBoxes = result.solutions.stream()
                    .mapToInt(s -> s.getPlacedCuboid().size()).sum();
            pw.printf(Locale.ROOT, "%d,%d,%d,%.2f%%%n",
                    groupIndex, result.solutions.size(), totalBoxes, result.avgUtilization);
        }
    }

    // ---------- 工具 ----------

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}