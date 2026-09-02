package org.example.visualizer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 排样结果可视化程序 —— 将 packing 结果渲染为原始多边形形状的 PNG 图片。
 *
 * <h3>设计目标</h3>
 * <ul>
 *   <li>独立运行：排样完成后，手动启动本程序进行可视化。</li>
 *   <li>原始形状：渲染 block 内每个 item 的 placedPoints 多边形，而非外接矩形。</li>
 *   <li>配色区分：Color=0 → 蓝色系，Color=1 → 红色系。</li>
 *   <li>组合 block 内每个 item 单独可辨：同一 block 的多个 item 按索引轮换色相，
 *       各自用不同颜色填充 + 深色描边，使内部边界清晰。</li>
 * </ul>
 *
 * <h3>数据来源</h3>
 * <ol>
 *   <li>{@code data/packResult2/{case}/polygons.json}
 *       —— NFPToBeamSearchBridge 生成的多边形顶点</li>
 *   <li>{@code data/packResult2/{case}/optimized.csv}
 *       —— LoadingTestRun 输出的排样放置结果</li>
 * </ol>
 *
 * <h3>使用方法</h3>
 * <pre>
 *   java PackingResultVisualizer [caseName] [resultDir] [outputDir]
 * </pre>
 *
 * <h3>坐标变换（均在 ×10 整数坐标系中计算）</h3>
 * <pre>
 *   R=0:  (px10, py10) → (X + px10, Y + py10)
 *   R=90: (px10, py10) → (X + py10, Y + rawL10 - px10)
 * </pre>
 */
public final class PackingResultVisualizer {

    // ---------- 默认路径 ----------
    private static final Path DEFAULT_RESULT_DIR    = Path.of("data", "packResult20");
    private static final Path DEFAULT_VISUAL_DIR    = Path.of("data", "visualization20");

    // ---------- 默认板材尺寸（mm），material.csv 不可用时回退 ----------
    private static final int DEFAULT_PLATE_LENGTH = 2440;
    private static final int DEFAULT_PLATE_WIDTH  = 1220;
    /** mm → 内部 ×10 整数坐标的缩放因子 */
    private static final int SCALE_FACTOR = 10;

    /**
     * Color=0 蓝色系调色板（按 item 索引轮换）。
     * 组合 block 内 item 0 用索引 0 的颜色，item 1 用索引 1，以此类推。
     * 超出数组长度时用取模回绕。
     */
    private static final Color[] BLUE_PALETTE = {
        new Color(173, 216, 230, 180),  // 浅蓝
        new Color(144, 224, 239, 180),  // 浅青
        new Color(183, 210, 240, 180),  // 薰衣草蓝
        new Color(200, 230, 255, 180),  // 极淡蓝
    };

    /**
     * Color=1 红色系调色板（同策略）。
     */
    private static final Color[] RED_PALETTE = {
        new Color(255, 182, 193, 180),  // 浅红
        new Color(255, 218, 185, 180),  // 浅桃
        new Color(255, 200, 210, 180),  // 浅玫红
        new Color(255, 160, 180, 180),  // 浅珊瑚
    };

    private static final Color STROKE_COLOR      = new Color(30, 30, 30, 220);
    /** item 单独描边色 —— 比整体描边略深，确保每个物品的轮廓线清晰 */
    private static final Color ITEM_STROKE_COLOR = new Color(10, 10, 10, 240);
    private static final Color BOARD_BG_COLOR    = new Color(250, 250, 250);
    private static final Color BOARD_BORDER_COLOR = new Color(80, 80, 80);
    private static final Color TITLE_COLOR       = new Color(30, 30, 30);

    // ---------- 渲染参数 ----------
    private static final double TARGET_RENDER_SIZE = 1400.0;
    private static final int PADDING = 50;
    private static final int TITLE_HEIGHT = 40;

    private PackingResultVisualizer() {}

    // ==================== 入口 ====================

    public static void main(String[] args) throws IOException {
        String caseName  = args.length > 0 ? args[0] : "";
        Path   resultDir = args.length > 1 ? Path.of(args[1]) : DEFAULT_RESULT_DIR;
        Path   outputDir = args.length > 2 ? Path.of(args[2]) : DEFAULT_VISUAL_DIR;

        if (caseName.isEmpty()) {
            processAllCases(resultDir, outputDir);
        } else {
            processCase(caseName, resultDir, outputDir);
        }
    }

    // ==================== 批量/单案例调度 ====================

    private static void processAllCases(Path resultDir, Path outputDir) throws IOException {
        File[] cases = resultDir.toFile().listFiles(File::isDirectory);
        if (cases == null || cases.length == 0) {
            System.err.println("[ERROR] No case directories found under: " + resultDir);
            return;
        }
        for (File caseDir : cases) {
            processCase(caseDir.getName(), resultDir, outputDir);
        }
    }

    private static void processCase(String caseName, Path resultDir, Path outputDir) throws IOException {
        Path optimizedCsv = resultDir.resolve(caseName).resolve("optimized.csv");
        Path polygonsJson = resultDir.resolve(caseName).resolve("polygons.json");

        if (!Files.exists(optimizedCsv)) {
            System.err.println("[SKIP] optimized.csv not found: " + optimizedCsv);
            return;
        }
        if (!Files.exists(polygonsJson)) {
            System.err.println("[SKIP] polygons.json not found: " + polygonsJson);
            return;
        }

        System.out.println("Visualizing: " + caseName);

        Map<String, BlockPolygonData> polygonMap = parsePolygonsJson(polygonsJson);
        List<PlacementRecord> placements = parseOptimizedCsv(optimizedCsv);
        int[] plateMm = readPlateDimensions(caseName, resultDir);

        Map<Integer, List<PlacementRecord>> grouped = new HashMap<>();
        for (PlacementRecord pr : placements) {
            grouped.computeIfAbsent(pr.boardNo, k -> new ArrayList<>()).add(pr);
        }

        Path caseOutputDir = outputDir.resolve(caseName);
        Files.createDirectories(caseOutputDir);

        for (Map.Entry<Integer, List<PlacementRecord>> entry : grouped.entrySet()) {
            int boardNo = entry.getKey();
            Path pngFile = caseOutputDir.resolve(String.format("container_%02d.png", boardNo));
            renderContainer(caseName, boardNo, entry.getValue(), polygonMap, plateMm, pngFile);
        }

        System.out.printf("  -> %s  (%d containers)%n", caseOutputDir, grouped.size());
    }

    // ==================== polygons.json 解析 ====================

    /**
     * 解析由 NFPToBeamSearchBridge 生成的多边形 JSON。
     *
     * <p>组合 block 的 items 数组中，每个 item 按出现顺序有自然索引。
     * 可视化时按 item 在 block 内的索引从调色板选取颜色，
     * 使同一 block 内的不同 item 各自可辨。</p>
     */
    private static Map<String, BlockPolygonData> parsePolygonsJson(Path file) throws IOException {
        Map<String, BlockPolygonData> map = new HashMap<>();
        String json = Files.readString(file, StandardCharsets.UTF_8);
        JsonArray root = JsonParser.parseString(json).getAsJsonArray();

        for (JsonElement elem : root) {
            JsonObject blockObj = elem.getAsJsonObject();
            String upi = blockObj.get("upi").getAsString();

            List<ItemPolygon> items = new ArrayList<>();
            JsonArray itemsArr = blockObj.getAsJsonArray("items");
            if (itemsArr != null) {
                for (JsonElement itemElem : itemsArr) {
                    JsonObject itemObj = itemElem.getAsJsonObject();
                    String id = itemObj.get("id").getAsString();
                    JsonArray ptsArr = itemObj.getAsJsonArray("points");
                    List<double[]> pts = jsonArrayToPoints(ptsArr);
                    items.add(new ItemPolygon(id, pts));
                }
            }

            JsonArray outlineArr = blockObj.getAsJsonArray("outline");
            List<double[]> outline = outlineArr != null ? jsonArrayToPoints(outlineArr) : List.of();

            map.put(upi, new BlockPolygonData(outline, items));
        }

        System.out.printf("  Parsed polygons: %d blocks%n", map.size());
        return map;
    }

    private static List<double[]> jsonArrayToPoints(JsonArray arr) {
        List<double[]> pts = new ArrayList<>();
        for (JsonElement elem : arr) {
            JsonArray pt = elem.getAsJsonArray();
            pts.add(new double[]{pt.get(0).getAsDouble(), pt.get(1).getAsDouble()});
        }
        return pts;
    }

    // ==================== optimized.csv 解析 ====================

    private static List<PlacementRecord> parseOptimizedCsv(Path file) throws IOException {
        List<PlacementRecord> records = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile(), StandardCharsets.UTF_8))) {
            br.readLine(); // 跳过表头
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 9) continue;

                PlacementRecord pr = new PlacementRecord();
                pr.boardNo = Integer.parseInt(cols[1].trim());
                pr.color   = cols[2].trim();
                pr.upi     = cols[3].trim();
                pr.x       = Integer.parseInt(cols[4].trim());
                pr.y       = Integer.parseInt(cols[5].trim());
                pr.orient  = Integer.parseInt(cols[6].trim());
                pr.rawL    = Double.parseDouble(cols[7].trim());
                pr.rawW    = Double.parseDouble(cols[8].trim());

                records.add(pr);
            }
        }
        System.out.printf("  Parsed placements: %d items%n", records.size());
        return records;
    }

    // ==================== 板材尺寸读取 ====================

    /**
     * 从 inputData2 目录的 material.csv 读取板材长宽（mm）。
     * material.csv 格式：Color,Length,Width,Grain（Length 为长边，Width 为短边）。
     * 若文件不存在则回退到默认 2440×1220。
     *
     * <p>修改理由：此前 renderContainer 按排样物品的实际占位推算板材大小，
     * 导致每张图片尺寸不一，无法直观感受板材上的空隙。
     * 改为从 material.csv 读取固定板材尺寸后，所有容器图片统一大小，
     * 空白区域即为未利用的板材空间。</p>
     */
    private static int[] readPlateDimensions(String caseName, Path resultDir) {
        /*
         * resultDir 例如 data/packResult4，其父目录下 inputData2/{case}/material.csv
         * 即为板材尺寸来源。
         */
        Path materialPath = resultDir.getParent().resolve("inputData2").resolve(caseName).resolve("material.csv");
        if (Files.exists(materialPath)) {
            try (BufferedReader br = new BufferedReader(new FileReader(materialPath.toFile(), StandardCharsets.UTF_8))) {
                br.readLine(); // 跳过表头 "Color,Length,Width,Grain"
                String line = br.readLine();
                if (line != null) {
                    String[] cols = line.split(",", -1);
                    if (cols.length >= 3) {
                        int length = (int) Double.parseDouble(cols[1].trim());
                        int width  = (int) Double.parseDouble(cols[2].trim());
                        System.out.printf("  Plate: %d x %d mm (from %s)%n", length, width, materialPath);
                        return new int[]{length, width};
                    }
                }
            } catch (IOException | NumberFormatException e) {
                System.err.println("[WARN] 读取 material.csv 失败: " + e.getMessage());
            }
        }
        System.out.printf("  Plate: %d x %d mm (default)%n", DEFAULT_PLATE_LENGTH, DEFAULT_PLATE_WIDTH);
        return new int[]{DEFAULT_PLATE_LENGTH, DEFAULT_PLATE_WIDTH};
    }

    // ==================== 渲染核心 ====================

    private static void renderContainer(String caseName, int boardNo,
                                        List<PlacementRecord> placements,
                                        Map<String, BlockPolygonData> polygonMap,
                                        int[] plateMm,
                                        Path outputFile) throws IOException {
        /*
         * 修改理由：此前 boardW/boardH 由排样物品的 max(pr.x + placedW) 推算，
         * 导致每张板材图片尺寸随内容变化，无法直观感受空隙空间。
         * 现改为使用 material.csv 中的固定板材长宽（mm × SCALE_FACTOR 转内部坐标），
         * 所有容器图片尺寸一致，空白区域即为未利用的板材面积。
         */
        int boardW = plateMm[0] * SCALE_FACTOR;
        int boardH = plateMm[1] * SCALE_FACTOR;

        double scale = TARGET_RENDER_SIZE / Math.max(boardW, boardH);
        int imgW = (int) (boardW * scale) + PADDING * 2;
        int imgH = (int) (boardH * scale) + PADDING * 2 + TITLE_HEIGHT;

        BufferedImage image = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            configureGraphics(g);

            g.setColor(BOARD_BG_COLOR);
            g.fillRect(0, 0, imgW, imgH);

            drawTitle(g, caseName, boardNo, placements, imgW);

            double offsetX = PADDING;
            double offsetY = TITLE_HEIGHT + PADDING;

            g.setColor(BOARD_BORDER_COLOR);
            g.setStroke(new BasicStroke(2.5f));
            g.drawRect((int) offsetX, (int) offsetY,
                    (int) (boardW * scale), (int) (boardH * scale));

            // Color=0 先画，Color=1 后画（按颜色分两层，保持视觉清爽）
            List<PlacementRecord> sorted = new ArrayList<>(placements);
            sorted.sort(Comparator.comparing(pr -> pr.color));

            for (PlacementRecord pr : sorted) {
                BlockPolygonData bd = polygonMap.get(pr.upi);
                if (bd == null || bd.items.isEmpty()) {
                    drawFallbackRect(g, pr, scale, offsetX, offsetY);
                } else {
                    drawBlockItems(g, pr, bd, scale, offsetX, offsetY);
                }
            }

            drawUtilization(g, placements, boardW, boardH, imgW);

        } finally {
            g.dispose();
        }

        Files.createDirectories(outputFile.getParent());
        ImageIO.write(image, "png", outputFile.toFile());
    }

    /**
     * 渲染 block 内所有 item 多边形，按 item 索引从调色板选取填充色，
     * 并为每个 item 单独绘制深色轮廓线以便区分。
     *
     * <p>设计理由：
     * 组合 block（UPI 含 "+"）由多个工件拼接而成。
     * 每个 item 从所属颜色系的调色板中按索引取色（索引超出数组长度时取模回绕）。
     * 单独为每个 item 绘制 1.5px 深色轮廓线，确保即使相邻 item 颜色相近时边界也清晰可见。</p>
     */
    private static void drawBlockItems(Graphics2D g, PlacementRecord pr,
                                       BlockPolygonData blockData,
                                       double scale, double offsetX, double offsetY) {
        Color[] palette = "1".equals(pr.color) ? RED_PALETTE : BLUE_PALETTE;
        int rawL10 = (int) (pr.rawL * 10);

        for (int idx = 0; idx < blockData.items.size(); idx++) {
            ItemPolygon item = blockData.items.get(idx);
            List<double[]> pts = item.points;
            if (pts.size() < 3) continue;

            Path2D path = buildTransformedPath(pts, pr, rawL10, scale, offsetX, offsetY);

            // 按 item 索引选填充色
            Color fill = palette[idx % palette.length];
            g.setColor(fill);
            g.fill(path);

            // 每个 item 单独描深色轮廓线（1.5px），使内部边界清晰可辨
            g.setColor(ITEM_STROKE_COLOR);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(path);
        }
    }

    /** 构建变换后的多边形路径 */
    private static Path2D buildTransformedPath(List<double[]> pts, PlacementRecord pr,
                                               int rawL10, double scale,
                                               double offsetX, double offsetY) {
        Path2D path = new Path2D.Double();
        boolean first = true;
        for (double[] p : pts) {
            double px10 = p[0] * 10;
            double py10 = p[1] * 10;
            double tx, ty;
            if (pr.orient == 0) {
                tx = pr.x + px10;
                ty = pr.y + py10;
            } else {
                tx = pr.x + py10;
                ty = pr.y + rawL10 - px10;
            }
            double sx = tx * scale + offsetX;
            double sy = ty * scale + offsetY;
            if (first) {
                path.moveTo(sx, sy);
                first = false;
            } else {
                path.lineTo(sx, sy);
            }
        }
        path.closePath();
        return path;
    }

    private static void drawFallbackRect(Graphics2D g, PlacementRecord pr,
                                         double scale, double offsetX, double offsetY) {
        Color fillColor = "1".equals(pr.color) ? RED_PALETTE[0] : BLUE_PALETTE[0];
        int placedW = (int) ((pr.orient == 0 ? pr.rawL : pr.rawW) * 10);
        int placedH = (int) ((pr.orient == 0 ? pr.rawW : pr.rawL) * 10);

        int rx = (int) (pr.x * scale + offsetX);
        int ry = (int) (pr.y * scale + offsetY);
        int rw = (int) (placedW * scale);
        int rh = (int) (placedH * scale);

        g.setColor(fillColor);
        g.fillRect(rx, ry, rw, rh);
        g.setColor(STROKE_COLOR);
        g.setStroke(new BasicStroke(1.0f));
        g.drawRect(rx, ry, rw, rh);
    }

    private static void drawTitle(Graphics2D g, String caseName, int boardNo,
                                  List<PlacementRecord> placements, int imgW) {
        long cnt0 = placements.stream().filter(p -> "0".equals(p.color)).count();
        long cnt1 = placements.stream().filter(p -> "1".equals(p.color)).count();

        g.setColor(TITLE_COLOR);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        String title = String.format(Locale.ROOT,
                "%s | Container %d | Items: %d (Color=0: %d, Color=1: %d)",
                caseName, boardNo, placements.size(), cnt0, cnt1);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(title, PADDING, fm.getAscent() + 8);
    }

    private static void drawUtilization(Graphics2D g, List<PlacementRecord> placements,
                                        int boardW, int boardH, int imgW) {
        double totalArea = 0;
        for (PlacementRecord pr : placements) {
            totalArea += pr.rawL * pr.rawW;
        }
        double boardArea = (boardW / 10.0) * (boardH / 10.0);
        double utilization = boardArea > 0 ? (totalArea / boardArea * 100) : 0;

        g.setColor(TITLE_COLOR);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        String text = String.format(Locale.ROOT,
                "Plate: %d×%d mm | Utilization: %.2f%%",
                boardW / 10, boardH / 10, utilization);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, imgW - PADDING - fm.stringWidth(text), PADDING + 8);
    }

    private static void configureGraphics(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    // ==================== 数据模型 ====================

    private static class BlockPolygonData {
        final List<double[]> outline;
        final List<ItemPolygon> items;

        BlockPolygonData(List<double[]> outline, List<ItemPolygon> items) {
            this.outline = outline;
            this.items = items;
        }
    }

    private static class ItemPolygon {
        final String id;
        final List<double[]> points;

        ItemPolygon(String id, List<double[]> points) {
            this.id = id;
            this.points = points;
        }
    }

    private static class PlacementRecord {
        int boardNo;
        String color;
        String upi;
        int x;
        int y;
        int orient;
        double rawL;
        double rawW;
    }
}