package org.example.application;

import org.example.beamsearch.application.LoadingTestRun;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * CLASSData 纯矩形基准的独立启动入口。
 *
 * <p>该入口不经过 NFP，也不会改变不规则图形 baseline 的求解路径。它只把 CLASSData
 * 的两列矩形尺寸转换为现有 Beam Search 的输入格式，并将全部工件标为优先件，从而复用
 * 原有的优先件排样与全局重排优化。</p>
 */
public final class RectanglePackingApplication {
    /** 可设为 CLASSData 根目录、其中一个编号子目录，或一个单独的矩形数据 TXT 文件。 */
    private static final Path INPUT_PATH = Path.of("data", "CLASSData");
    /** 每个矩形案例的结果写入该目录下与输入相同的相对层级。 */
    private static final Path OUTPUT_DIRECTORY = Path.of("data", "CLASSDataResult_NOROTATION3");
    /** 全部矩形共用的旋转设置：true 可旋转 90°，false 保持输入方向。 */
    private static final boolean ALLOW_ROTATION = false;

    private static final String PRIORITY_COLOR = "1";
    private static final String WORKPIECE_HEADER =
            "BatchNo,UPI,Qty,Color,Length,Width,IsSpecial,Rotatable,ActualWorkpieceArea";

    private RectanglePackingApplication() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 0) {
            System.err.println("请在 RectanglePackingApplication 顶部设置 INPUT_PATH、OUTPUT_DIRECTORY 和 ALLOW_ROTATION。");
            return;
        }
        run(INPUT_PATH, OUTPUT_DIRECTORY, ALLOW_ROTATION);
    }

    /**
     * 执行一个矩形文件或一组矩形文件，供测试代码和 IDE 入口复用。
     *
     * @param inputPath CLASSData 根目录、编号目录或单个 TXT 文件
     * @param outputDirectory 结果根目录
     * @param allowRotation 是否允许所有矩形旋转 90°
     */
    public static void run(Path inputPath,
                           Path outputDirectory,
                           boolean allowRotation) throws IOException {
        List<Path> cases = resolveCases(inputPath);
        if (cases.isEmpty()) {
            throw new IOException("未找到矩形案例 TXT 文件: " + inputPath);
        }

        Path normalizedInput = inputPath.toAbsolutePath().normalize();
        Path normalizedOutput = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedOutput);
        Path relativeRoot = Files.isDirectory(normalizedInput)
                ? normalizedInput : normalizedInput.getParent();

        for (Path caseFile : cases) {
            BoardSize board = readBoardSize(caseFile);
            List<Rectangle> rectangles = readRectangles(caseFile);
            Path caseOutputDirectory = resolveCaseOutputDirectory(
                    caseFile, relativeRoot, normalizedOutput);
            Path bridgeDirectory = caseOutputDirectory.resolve("input");
            Files.createDirectories(caseOutputDirectory);
            Files.createDirectories(bridgeDirectory);

            Path materialFile = bridgeDirectory.resolve("material.csv");
            Path workpieceFile = bridgeDirectory.resolve("workpiece.csv");
            writeMaterial(materialFile, board);
            writeWorkpieces(workpieceFile, caseFile, rectangles, allowRotation);

            System.out.printf(Locale.ROOT,
                    "矩形案例: %s，工件=%d，板材=%.3f x %.3f，允许旋转=%s%n",
                    caseFile.getFileName(), rectangles.size(), board.length(), board.width(), allowRotation);
            LoadingTestRun.runWithImprove(
                    materialFile.toString(), workpieceFile.toString(), caseOutputDirectory.toString());
        }
    }

    private static List<Path> resolveCases(Path inputPath) throws IOException {
        if (Files.isRegularFile(inputPath)) {
            if (!inputPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                throw new IOException("矩形案例必须是 .txt 文件: " + inputPath);
            }
            return List.of(inputPath.toAbsolutePath().normalize());
        }
        if (!Files.isDirectory(inputPath)) {
            throw new IOException("矩形输入路径不存在: " + inputPath);
        }
        try (var files = Files.walk(inputPath)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static BoardSize readBoardSize(Path caseFile) throws IOException {
        Path groupDirectory = caseFile.getParent();
        if (groupDirectory == null || groupDirectory.getParent() == null) {
            throw new IOException("无法从案例路径定位 CLASSData 分组: " + caseFile);
        }
        String group = groupDirectory.getFileName().toString();
        Path materialFile = groupDirectory.getParent().resolve("material_" + group + ".csv");
        if (!Files.isRegularFile(materialFile)) {
            throw new IOException("缺少案例对应的板材文件: " + materialFile);
        }
        List<String> lines = Files.readAllLines(materialFile, StandardCharsets.UTF_8);
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] columns = line.split(",", -1);
            if (columns.length < 3) {
                throw new IOException("板材文件格式错误: " + materialFile + " 第 " + (index + 1) + " 行");
            }
            double length = parsePositive(columns[1], materialFile, index + 1);
            double width = parsePositive(columns[2], materialFile, index + 1);
            return new BoardSize(length, width);
        }
        throw new IOException("板材文件没有有效规格: " + materialFile);
    }

    private static List<Rectangle> readRectangles(Path caseFile) throws IOException {
        List<Rectangle> rectangles = new ArrayList<>();
        List<String> lines = Files.readAllLines(caseFile, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] dimensions = line.split("\\s+");
            if (dimensions.length != 2) {
                throw new IOException("矩形案例每行必须仅含两个尺寸: " + caseFile + " 第 " + (index + 1) + " 行");
            }
            rectangles.add(new Rectangle(
                    parsePositive(dimensions[0], caseFile, index + 1),
                    parsePositive(dimensions[1], caseFile, index + 1)));
        }
        if (rectangles.isEmpty()) {
            throw new IOException("矩形案例为空: " + caseFile);
        }
        return rectangles;
    }

    private static void writeMaterial(Path materialFile, BoardSize board) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(materialFile, StandardCharsets.UTF_8)) {
            writer.write("Color,Length,Width,Grain");
            writer.newLine();
            writer.write(PRIORITY_COLOR + "," + board.length() + "," + board.width() + ",0");
            writer.newLine();
        }
    }

    private static void writeWorkpieces(Path workpieceFile,
                                        Path caseFile,
                                        List<Rectangle> rectangles,
                                        boolean allowRotation) throws IOException {
        String caseName = stripExtension(caseFile.getFileName().toString());
        String rotationFlag = allowRotation ? "0" : "1";
        try (BufferedWriter writer = Files.newBufferedWriter(workpieceFile, StandardCharsets.UTF_8)) {
            writer.write(WORKPIECE_HEADER);
            writer.newLine();
            for (int index = 0; index < rectangles.size(); index++) {
                Rectangle rectangle = rectangles.get(index);
                String itemId = caseName + "-" + String.format(Locale.ROOT, "%04d", index + 1);
                double area = rectangle.length() * rectangle.width();
                writer.write(String.join(",",
                        caseName,
                        itemId,
                        "1",
                        PRIORITY_COLOR,
                        Double.toString(rectangle.length()),
                        Double.toString(rectangle.width()),
                        "0",
                        rotationFlag,
                        Double.toString(area)));
                writer.newLine();
            }
        }
    }

    private static Path resolveCaseOutputDirectory(Path caseFile,
                                                    Path relativeRoot,
                                                    Path outputRoot) {
        Path relativeFile = relativeRoot.relativize(caseFile.toAbsolutePath().normalize());
        Path relativeParent = relativeFile.getParent();
        String caseName = stripExtension(relativeFile.getFileName().toString());
        return relativeParent == null ? outputRoot.resolve(caseName) : outputRoot.resolve(relativeParent).resolve(caseName);
    }

    private static double parsePositive(String raw, Path source, int lineNumber) throws IOException {
        try {
            double value = Double.parseDouble(raw.strip());
            if (Double.isFinite(value) && value > 0.0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Converted below into a source-aware validation error.
        }
        throw new IOException("尺寸必须是正数: " + source + " 第 " + lineNumber + " 行");
    }

    private static String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private record BoardSize(double length, double width) {
    }

    private record Rectangle(double length, double width) {
    }
}
