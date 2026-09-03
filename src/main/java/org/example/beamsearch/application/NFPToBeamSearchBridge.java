package org.example.beamsearch.application;

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
 * NFP 组块结果 → BeamSearch 排样 的桥接主程序。
 *
 * 流程:
 *   1. 读取 data/newOutputData10/{case}.txt（NFP 拼接结果）
 *   2. 读取 data/inputData/{case}.json（获取板材尺寸）
 *   3. 生成 data/materialData/{case}/material.csv（板材信息）
 *   4. 生成 data/materialData/{case}/workpiece（矩形排样输入，逗号分隔）
 *   5. 调用 LoadingTestRun.runWithImprove() 执行排样
 *   6. 生成 data/packResult5/{case}/polygons.json（供独立可视化使用）
 *
 * 生成文件格式:
 *   material.csv（逗号分隔）:
 *     Color,Length,Width,Grain
 *     0,2440,1220,0
 *
 *   workpiece（逗号分隔，首行为表头会被跳过）:
 *     BatchNo UPI Qty Color Length Width IsSpecial Rotatable
 *
 *   polygons.json（独立可视化用）:
 *     JSON 数组，每项对应一个 block，包含 UPI、外轮廓、子 item 的 id 及多边形顶点。
 *     组合 block 的多个 item 分别列出，可视化程序按 item 索引分配不同色相，
 *     使每个 item 单独可辨。
 */
public class NFPToBeamSearchBridge {

    private static final Path DEFAULT_OUTPUT_DIR = Path.of("data", "NFPJoint1");
    private static final Path DEFAULT_INPUT_DIR  = Path.of("data", "inputData");
    private static final Path DEFAULT_BRIDGE_DIR = Path.of("data", "material1");
    private static final Path DEFAULT_RESULT_DIR = Path.of("data", "Result1");

    // ---------- 数据模型 ----------

    private static class ItemRecord {
        final String id;
        final List<double[]> originalPoints;     // 原始形状顶点（mm）
        final List<double[]> placedPoints;       // NFP 排布后的形状顶点（mm）

        ItemRecord(String id, List<double[]> originalPoints, List<double[]> placedPoints) {
            this.id = id;
            this.originalPoints = originalPoints;
            this.placedPoints = placedPoints;
        }
    }

    private static class BlockRecord {
        final String id;
        final boolean backFrontPriority;
        final List<Integer> rotate;
        final double boxArea;
        final List<double[]> outline;
        /** block 内包含的子 item 多边形（简单 block 通常 1 项，组合 block 可能多项） */
        final List<ItemRecord> items;

        BlockRecord(String id, boolean backFrontPriority, List<Integer> rotate,
                    double boxArea, List<double[]> outline) {
            this(id, backFrontPriority, rotate, boxArea, outline, List.of());
        }

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
        Path outputDir  = args.length > 0 ? Path.of(args[0]) : DEFAULT_OUTPUT_DIR;
        Path inputDir   = args.length > 1 ? Path.of(args[1]) : DEFAULT_INPUT_DIR;
        Path bridgeDir  = args.length > 2 ? Path.of(args[2]) : DEFAULT_BRIDGE_DIR;
        Path resultDir  = args.length > 3 ? Path.of(args[3]) : DEFAULT_RESULT_DIR;

        Files.createDirectories(bridgeDir);
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
                System.out.println("[跳过] 找不到 JSON: " + jsonFile);
                continue;
            }
            processCase(caseName, txtFile, jsonFile, bridgeDir, resultDir);
        }
    }

    // ---------- 单案例处理 ----------

    static void processCase(String caseName, Path txtFile, Path jsonFile,
                            Path bridgeDir, Path resultDir) throws IOException {
        System.out.println("处理案例: " + caseName);

        List<BlockRecord> blocks = parseBlocks(txtFile);
        if (blocks.isEmpty()) {
            System.out.println("  无 block，跳过");
            return;
        }
        System.out.printf("  解析 block: %d 个%n", blocks.size());

        double[] plate = readPlate(jsonFile);
        double plateW = plate[0];
        double plateH = plate[1];
        System.out.printf("  板材: %.0f x %.0f%n", plateW, plateH);

        Set<String> colors = new LinkedHashSet<>();
        for (BlockRecord block : blocks) {
            colors.add(block.backFrontPriority ? "1" : "0");
        }
        System.out.printf("  颜色组: %s%n", colors);

        Path caseBridgeDir = bridgeDir.resolve(caseName);
        Files.createDirectories(caseBridgeDir);

        Path materialPath = caseBridgeDir.resolve("material.csv");
        writeMaterial(materialPath, plateW, plateH, colors);
        System.out.println("  写入: " + materialPath);

        Path workpiecePath = caseBridgeDir.resolve("workpiece");
        writeWorkpiece(workpiecePath, caseName, blocks);
        System.out.println("  写入: " + workpiecePath);

        Path caseResultDir = resultDir.resolve(caseName);
        Files.createDirectories(caseResultDir);
        System.out.println("  开始排样...");
        long start = System.currentTimeMillis();

        String[] summary = LoadingTestRun.runWithImprove(
                materialPath.toString(),
                workpiecePath.toString(),
                caseResultDir.toString() + File.separator);

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("  完成 (%.1fs)%n", elapsed / 1000.0);
        if (summary != null) {
            System.out.printf("  结果: %s 工件, %s 张板, 利用率 %s, 耗时 %s%n",
                    summary[1], summary[2], summary[3], summary[4]);
        }

        Path polygonJsonPath = caseResultDir.resolve("polygons.json");
        writePolygonJson(polygonJsonPath, blocks);
        System.out.println("  写入: " + polygonJsonPath);
    }

    // ---------- outputData 解析 ----------

    /**
     * 解析 outputData 文本，提取每个 block 及其子 item 的多边形顶点。
     * <p>
     * items 解析：组合 block（UPI 含 "+"）内有多个 item，每个 item 有独立的 placedPoints。
     * 简单 block 无显式 items 节时，用 outline 作为唯一 item。
     * </p>
     * <p>
     * 解析策略说明（供可视化）：
     * 每个 item 的 placedPoints 是 NFP 算法在 block 内部排布后的多边形顶点（mm 坐标）。
     * 组合 block 内的多个 item 各自保留独立的多边形数据，
     * 可视化时按 item 在列表中的索引分配不同色相即可使每个 item 单独可辨。
     * </p>
     */
    static List<BlockRecord> parseBlocks(Path file) throws IOException {
        List<BlockRecord> blocks = new ArrayList<>();
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = text.split("\\R");

        int i = 0;
        while (i < lines.length) {
            if (!lines[i].startsWith("block ")) { i++; continue; }

            String id = "";
            boolean priority = false;
            List<Integer> rotate = List.of(0);
            double boxArea = 0;
            List<double[]> outline = List.of();

            List<ItemRecord> items = new ArrayList<>();
            boolean inItemsSection = false;
            String currentItemId = null;
            List<double[]> currentOriginalPoints = null;
            List<double[]> currentPlacedPoints = null;

            i++;
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
                    outline = parsePoints(line.substring("outline=".length()));
                }

                if (line.equals("items=") || line.startsWith("items=")) {
                    inItemsSection = true;
                    i++;
                    while (i < lines.length && !lines[i].startsWith("block ")) {
                        String rawLine = lines[i];
                        String itemLine = rawLine.trim();
                        if (itemLine.isEmpty()) { i++; continue; }
                        /*
                         * 修改理由：trim() 后无法用 startsWith("  ") 判断缩进，
                         * 此前会导致读到第一个 item 的属性行就错误退出 items 段，
                         * 使组合 block 的第二个及后续 item 全部丢失。
                         * 改用未裁剪的 rawLine 判断：2 空格接 "- " 为新 item，4 空格为 item 属性。
                         */
                        if (!rawLine.startsWith("  -") && !rawLine.startsWith("    ")) break;
                        if (itemLine.startsWith("- id=")) {
                            flushItem(items, currentItemId, currentOriginalPoints, currentPlacedPoints, outline);
                            currentItemId = itemLine.substring("- id=".length());
                            currentOriginalPoints = null;
                            currentPlacedPoints = null;
                        } else if (itemLine.startsWith("originalPoints=")) {
                            currentOriginalPoints = parsePoints(itemLine.substring("originalPoints=".length()));
                        } else if (itemLine.startsWith("placedPoints=")) {
                            currentPlacedPoints = parsePoints(itemLine.substring("placedPoints=".length()));
                        }
                        i++;
                    }
                    flushItem(items, currentItemId, currentOriginalPoints, currentPlacedPoints, outline);
                    currentItemId = null;
                    currentOriginalPoints = null;
                    currentPlacedPoints = null;
                    inItemsSection = false;
                    continue;
                }
                i++;
            }

            if (!id.isEmpty()) {
                blocks.add(new BlockRecord(id, priority, rotate, boxArea, outline, items));
            }
        }
        return blocks;
    }

    private static void flushItem(List<ItemRecord> items, String itemId,
                                  List<double[]> originalPts, List<double[]> placedPts,
                                  List<double[]> fallbackOutline) {
        if (itemId == null) return;
        List<double[]> orig = (originalPts != null) ? originalPts
                : ((placedPts != null) ? placedPts : fallbackOutline);
        List<double[]> plac = (placedPts != null) ? placedPts
                : ((originalPts != null) ? originalPts : fallbackOutline);
        items.add(new ItemRecord(itemId, orig, plac));
    }

    private static List<Integer> parseIntList(String raw) {
        raw = raw.trim();
        if (raw.equals("[]")) return List.of(0);
        if (raw.startsWith("[") && raw.endsWith("]")) raw = raw.substring(1, raw.length() - 1);
        return Arrays.stream(raw.split(",")).map(String::trim).map(Integer::parseInt).toList();
    }

    private static List<double[]> parsePoints(String raw) {
        raw = raw.trim();
        List<double[]> pts = new ArrayList<>();
        Matcher m = Pattern.compile("\\[\\s*([\\d.\\-]+)\\s*,\\s*([\\d.\\-]+)\\s*]").matcher(raw);
        while (m.find()) pts.add(new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))});
        return pts;
    }

    // ---------- 板材读取 ----------

    static double[] readPlate(Path jsonFile) throws IOException {
        String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray plates = root.getAsJsonArray("plates");
        double w = 2440, h = 1220;
        if (plates != null && plates.size() > 0) {
            JsonObject first = plates.get(0).getAsJsonObject();
            JsonElement jw = first.get("width");
            JsonElement jh = first.get("height");
            if (jw != null) w = jw.getAsDouble();
            if (jh != null) h = jh.getAsDouble();
        }
        return new double[]{w, h};
    }

    // ---------- 文件生成 ----------

    static void writeMaterial(Path path, double plateW, double plateH, Set<String> colors) throws IOException {
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(path.toFile()), StandardCharsets.UTF_8)))) {
            pw.println("Color,Length,Width,Grain");
            for (String color : colors) {
                pw.printf(Locale.ROOT, "%s,%.0f,%.0f,0%n", color, plateW, plateH);
            }
        }
    }

    static void writeWorkpiece(Path path, String caseName, List<BlockRecord> blocks) throws IOException {
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(path.toFile()), StandardCharsets.UTF_8)))) {
            pw.println("BatchNo,UPI,Qty,Color,Length,Width,IsSpecial,Rotatable");

            int seq = 0;
            for (BlockRecord block : blocks) {
                double[] bbox = computeBBox(blockPlacedPoints(block));
                double w = bbox[2] - bbox[0];
                double h = bbox[3] - bbox[1];

                String batchNo   = caseName;
                String upi       = block.id.isEmpty() ? String.valueOf(++seq) : block.id;
                String qty       = "1";
                String color     = block.backFrontPriority ? "1" : "0";
                String isSpecial = "0";
                String rotatable = canRotateInBeamSearch(block.rotate) ? "0" : "1";

                pw.printf(Locale.ROOT, "%s,%s,%s,%s,%.4f,%.4f,%s,%s%n",
                        batchNo, upi, qty, color, w, h, isSpecial, rotatable);
            }
        }
    }

    /**
     * 生成多边形 JSON 文件 —— 供独立可视化程序使用。
     *
     * <p>设计理由：
     * 排样结果 optimized.csv 仅包含放置坐标和外接矩形尺寸，
     * 可视化时需要还原每个 item 的原始多边形形状。
     * 组合 block（UPI 含 "+"）内多个 item 各自独立列出，
     * 可视化程序按 item 在数组中的索引轮换色相，
     * 使同一 block 内的不同 item 边界清晰可辨。</p>
     *
     * <p>格式：</p>
     * <pre>{@code
     * [
     *   {
     *     "upi": "28239561053+28248451192",
     *     "outline": [[1765.0, 0.0], ...],
     *     "items": [
     *       { "id": "28239561053", "points": [[...]] },
     *       { "id": "28248451192", "points": [[...]] }
     *     ]
     *   }
     * ]
     * }</pre>
     */
    static void writePolygonJson(Path path, List<BlockRecord> blocks) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        int blockCount = blocks.size();
        for (int bi = 0; bi < blockCount; bi++) {
            BlockRecord block = blocks.get(bi);
            sb.append("  {\n");
            sb.append("    \"upi\": \"").append(escapeJson(block.id)).append("\",\n");
            sb.append("    \"outline\": ").append(pointsToJson(block.outline)).append(",\n");

            List<ItemRecord> srcItems = block.items.isEmpty()
                    ? List.of(new ItemRecord(block.id, block.outline, block.outline))
                    : block.items;

            sb.append("    \"items\": [\n");
            for (int ii = 0; ii < srcItems.size(); ii++) {
                ItemRecord item = srcItems.get(ii);
                sb.append("      {\n");
                sb.append("        \"id\": \"").append(escapeJson(item.id)).append("\",\n");
                sb.append("        \"points\": ").append(pointsToJson(item.placedPoints)).append("\n");
                sb.append("      }");
                if (ii < srcItems.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("    ]\n");

            sb.append("  }");
            if (bi < blockCount - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String pointsToJson(List<double[]> pts) {
        if (pts.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            sb.append(String.format(Locale.ROOT, "[%.4f,%.4f]", p[0], p[1]));
            if (i < pts.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

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
        // 矩形化必须使用拼接后的 placedPoints：它们已经包含第一阶段确定的旋转和平移。
        // originalPoints 仍是单件初始局部坐标，不能代表组合块在块内的真实占位。
        List<double[]> points = new ArrayList<>();
        for (ItemRecord item : block.items) {
            points.addAll(item.placedPoints);
        }
        if (!points.isEmpty()) {
            return points;
        }
        // 兼容旧格式：历史 outputData 可能没有 items 段，此时只能退回到 outline。
        return block.outline;
    }

    private static boolean canRotateInBeamSearch(List<Integer> rotations) {
        // BeamSearch 的矩形排样只区分 0° 与 90° 两种摆放；允许 90° 或 270° 都表示可换成长宽方向。
        return rotations.contains(90) || rotations.contains(270);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}