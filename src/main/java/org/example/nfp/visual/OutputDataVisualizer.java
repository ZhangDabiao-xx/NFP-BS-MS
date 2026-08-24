package org.example.nfp.visual;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

public final class OutputDataVisualizer {

    private static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("data", "NFPresult1");
    private static final Path DEFAULT_PICTURE_DIRECTORY = Path.of("data", "NFPpicture1");

    // 目标渲染尺寸越大，图片细节越清楚；过小时会自动放大。
    private static final double TARGET_RENDER_SIZE = 900.0;
    private static final double MAX_RENDER_SCALE = 4.0;
    private static final int PADDING = 60;
    private static final int TITLE_HEIGHT = 48;

    private static final Color BACKGROUND_COLOR = Color.WHITE;
    private static final Color OUTLINE_COLOR = new Color(30, 30, 30);
    private static final Color BORDER_COLOR = new Color(210, 210, 210);
    private static final Color TITLE_COLOR = new Color(40, 40, 40);
    private static final Color ITEM_STROKE_COLOR = new Color(60, 60, 60);

    private static final Color[] ITEM_COLORS = new Color[] {
            new Color(239, 83, 80, 140),
            new Color(255, 167, 38, 140),
            new Color(102, 187, 106, 140),
            new Color(66, 165, 245, 140),
            new Color(171, 71, 188, 140),
            new Color(38, 198, 218, 140),
            new Color(255, 238, 88, 140),
            new Color(141, 110, 99, 140),
            new Color(126, 87, 194, 140),
            new Color(77, 182, 172, 140),
            new Color(244, 143, 177, 140),
            new Color(156, 204, 101, 140)
    };

    private OutputDataVisualizer() {
    }

    public static void main(String[] args) throws IOException {
        Path outputDirectory = args.length > 0 ? Path.of(args[0]) : DEFAULT_OUTPUT_DIRECTORY;
        Path pictureDirectory = args.length > 1 ? Path.of(args[1]) : DEFAULT_PICTURE_DIRECTORY;
        visualizeDirectory(outputDirectory, pictureDirectory);
    }

    public static void visualizeDirectory(Path outputDirectory, Path pictureDirectory) throws IOException {
        Files.createDirectories(pictureDirectory);

        List<Path> outputFiles;
        try (var stream = Files.list(outputDirectory)) {
            outputFiles = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        for (Path outputFile : outputFiles) {
            CaseData caseData = parseCaseFile(outputFile);
            if (caseData.blocks.isEmpty()) {
                continue;
            }

            Path caseDirectory = pictureDirectory.resolve(caseData.caseName);
            Files.createDirectories(caseDirectory);

            for (int i = 0; i < caseData.blocks.size(); i++) {
                BlockRecord block = caseData.blocks.get(i);
                String safeBlockId = sanitizeFileName(block.blockId == null ? "block" : block.blockId);
                Path imageFile = caseDirectory.resolve(String.format(Locale.ROOT, "block_%04d_%s.png", i + 1, safeBlockId));
                renderBlock(block, caseData.caseName, i + 1, imageFile);
            }

            System.out.printf("%s -> %s, blocks=%d%n", outputFile.getFileName(), caseDirectory, caseData.blocks.size());
        }
    }

    private static CaseData parseCaseFile(Path outputFile) throws IOException {
        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        CaseData caseData = new CaseData(replaceExtension(outputFile.getFileName().toString(), ""));

        BlockRecord currentBlock = null;
        ItemRecord currentItem = null;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("block ")) {
                currentBlock = new BlockRecord();
                caseData.blocks.add(currentBlock);
                currentItem = null;
                continue;
            }

            if (currentBlock == null) {
                continue;
            }

            if (line.equals("items=")) {
                currentItem = null;
                continue;
            }

            if (line.startsWith("- id=")) {
                currentItem = new ItemRecord();
                currentItem.id = line.substring("- id=".length());
                currentBlock.items.add(currentItem);
                continue;
            }

            if (line.startsWith("id=")) {
                currentBlock.blockId = line.substring("id=".length());
                continue;
            }
            if (line.startsWith("BackFrontPriority=")) {
                boolean value = Boolean.parseBoolean(line.substring("BackFrontPriority=".length()));
                currentBlock.backFrontPriority = value;
                continue;
            }
            if (line.startsWith("score2=")) {
                currentBlock.score2 = parseDouble(line.substring("score2=".length()));
                continue;
            }
            if (line.startsWith("boxArea=")) {
                currentBlock.boxArea = parseDouble(line.substring("boxArea=".length()));
                continue;
            }
            if (line.startsWith("outline=")) {
                currentBlock.outline = parsePoints(line.substring("outline=".length()));
                continue;
            }
            if (line.startsWith("combinedCoordinates=")) {
                currentBlock.combinedCoordinates = parsePoints(line.substring("combinedCoordinates=".length()));
                continue;
            }
            if (line.startsWith("originalPoints=")) {
                if (currentItem != null) {
                    currentItem.originalPoints = parsePoints(line.substring("originalPoints=".length()));
                }
                continue;
            }
            if (line.startsWith("placedPoints=")) {
                if (currentItem != null) {
                    currentItem.placedPoints = parsePoints(line.substring("placedPoints=".length()));
                }
            }
        }

        return caseData;
    }

    private static void renderBlock(BlockRecord block, String caseName, int blockIndex, Path imageFile) throws IOException {
        List<PointData> allPoints = new ArrayList<>();
        allPoints.addAll(block.outline);
        allPoints.addAll(block.combinedCoordinates);
        for (ItemRecord item : block.items) {
            allPoints.addAll(item.placedPoints);
            allPoints.addAll(item.originalPoints);
        }

        Bounds bounds = Bounds.fromPoints(allPoints);
        double scale = computeScale(bounds);
        int width = Math.max(420, (int) Math.ceil(bounds.width() * scale) + PADDING * 2);
        int height = Math.max(420, (int) Math.ceil(bounds.height() * scale) + PADDING * 2 + TITLE_HEIGHT);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configureGraphics(graphics);
            graphics.setColor(BACKGROUND_COLOR);
            graphics.fillRect(0, 0, width, height);

            drawTitle(graphics, caseName, blockIndex, block, width);

            double offsetX = PADDING - bounds.minX * scale;
            double offsetY = TITLE_HEIGHT + PADDING - bounds.minY * scale;

            for (int i = 0; i < block.items.size(); i++) {
                ItemRecord item = block.items.get(i);
                Color fillColor = ITEM_COLORS[i % ITEM_COLORS.length];
                drawPolygon(graphics, item.placedPoints, scale, offsetX, offsetY, fillColor, ITEM_STROKE_COLOR, 2.0f);
                drawItemLabel(graphics, item, scale, offsetX, offsetY, i + 1);
            }

            // 外轮廓放到最上层，方便一眼看出拼接后的整体边界。
            if (!block.outline.isEmpty()) {
                drawPolygon(graphics, block.outline, scale, offsetX, offsetY, new Color(0, 0, 0, 0), OUTLINE_COLOR, 3.0f);
            }

            graphics.setColor(BORDER_COLOR);
            graphics.drawRect(10, TITLE_HEIGHT - 8, width - 20, height - TITLE_HEIGHT - 14);
        } finally {
            graphics.dispose();
        }

        Files.createDirectories(imageFile.getParent());
        ImageIO.write(image, "png", imageFile.toFile());
    }

    private static void drawTitle(Graphics2D graphics, String caseName, int blockIndex, BlockRecord block, int width) {
        graphics.setColor(TITLE_COLOR);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        String title = String.format(Locale.ROOT,
                "%s | block %d | id=%s | score2=%.4f",
                caseName,
                blockIndex,
                block.blockId == null ? "unknown" : block.blockId,
                block.score2);
        FontMetrics fontMetrics = graphics.getFontMetrics();
        graphics.drawString(title, PADDING, Math.max(24, fontMetrics.getAscent() + 16));

        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        String subTitle = String.format(Locale.ROOT,
                "items=%d, backFrontPriority=%s, boxArea=%.2f",
                block.items.size(),
                block.backFrontPriority,
                block.boxArea);
        graphics.drawString(subTitle, PADDING, TITLE_HEIGHT - 14);
    }

    private static void drawItemLabel(Graphics2D graphics, ItemRecord item, double scale, double offsetX, double offsetY, int index) {
        if (item.placedPoints.isEmpty()) {
            return;
        }

        PointData center = centroid(item.placedPoints);
        int x = (int) Math.round(center.x * scale + offsetX);
        int y = (int) Math.round(center.y * scale + offsetY);
        graphics.setColor(new Color(20, 20, 20));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        String label = item.id == null ? String.valueOf(index) : item.id;
        graphics.drawString(label, x + 4, y - 4);
    }

    private static void drawPolygon(Graphics2D graphics,
                                    List<PointData> points,
                                    double scale,
                                    double offsetX,
                                    double offsetY,
                                    Color fillColor,
                                    Color strokeColor,
                                    float strokeWidth) {
        if (points.size() < 3) {
            return;
        }

        Path2D path = new Path2D.Double();
        PointData firstPoint = points.get(0);
        path.moveTo(firstPoint.x * scale + offsetX, firstPoint.y * scale + offsetY);
        for (int i = 1; i < points.size(); i++) {
            PointData point = points.get(i);
            path.lineTo(point.x * scale + offsetX, point.y * scale + offsetY);
        }
        path.closePath();

        if (fillColor.getAlpha() > 0) {
            graphics.setColor(fillColor);
            graphics.fill(path);
        }

        graphics.setColor(strokeColor);
        graphics.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.draw(path);
    }

    private static void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static double computeScale(Bounds bounds) {
        double maxDimension = Math.max(bounds.width(), bounds.height());
        if (maxDimension <= 0.0) {
            return 1.0;
        }
        double scale = TARGET_RENDER_SIZE / maxDimension;
        scale = Math.max(1.0, scale);
        return Math.min(MAX_RENDER_SCALE, scale);
    }

    private static List<PointData> parsePoints(String value) {
        List<PointData> points = new ArrayList<>();
        StringBuilder numberBuffer = new StringBuilder();
        Double currentX = null;

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch) || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
                numberBuffer.append(ch);
                continue;
            }

            if (ch == ',' && !numberBuffer.isEmpty()) {
                currentX = parseDouble(numberBuffer.toString());
                numberBuffer.setLength(0);
                continue;
            }

            if (ch == ']' && !numberBuffer.isEmpty()) {
                double y = parseDouble(numberBuffer.toString());
                numberBuffer.setLength(0);
                if (currentX != null) {
                    points.add(new PointData(currentX, y));
                    currentX = null;
                }
            }
        }

        return points;
    }

    private static PointData centroid(List<PointData> points) {
        double sumX = 0.0;
        double sumY = 0.0;
        for (PointData point : points) {
            sumX += point.x;
            sumY += point.y;
        }
        return new PointData(sumX / points.size(), sumY / points.size());
    }

    private static double parseDouble(String text) {
        return Double.parseDouble(text);
    }

    private static String replaceExtension(String fileName, String extension) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return fileName + extension;
        }
        return fileName.substring(0, dotIndex) + extension;
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static final class CaseData {
        private final String caseName;
        private final List<BlockRecord> blocks = new ArrayList<>();

        private CaseData(String caseName) {
            this.caseName = caseName;
        }
    }

    private static final class BlockRecord {
        private String blockId;
        private boolean backFrontPriority;
        private double score2;
        private double boxArea;
        private List<PointData> outline = new ArrayList<>();
        private List<PointData> combinedCoordinates = new ArrayList<>();
        private final List<ItemRecord> items = new ArrayList<>();
    }

    private static final class ItemRecord {
        private String id;
        private List<PointData> originalPoints = new ArrayList<>();
        private List<PointData> placedPoints = new ArrayList<>();
    }

    private static final class PointData {
        private final double x;
        private final double y;

        private PointData(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Bounds {
        private final double minX;
        private final double minY;
        private final double maxX;
        private final double maxY;

        private Bounds(double minX, double minY, double maxX, double maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        private static Bounds fromPoints(List<PointData> points) {
            if (points.isEmpty()) {
                return new Bounds(0, 0, 1, 1);
            }

            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (PointData point : points) {
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
            }
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
                return new Bounds(0, 0, 1, 1);
            }
            if (maxX - minX < 1e-6) {
                maxX = minX + 1.0;
            }
            if (maxY - minY < 1e-6) {
                maxY = minY + 1.0;
            }
            return new Bounds(minX, minY, maxX, maxY);
        }

        private double width() {
            return maxX - minX;
        }

        private double height() {
            return maxY - minY;
        }
    }
}