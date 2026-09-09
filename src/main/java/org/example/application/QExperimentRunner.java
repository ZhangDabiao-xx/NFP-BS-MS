package org.example.application;

import org.example.qlearning.QLearningConfig;
import org.example.qlearning.QMode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 在不新增 main 入口的前提下，自动执行 Q-learning 基线、连续训练、评估与结果汇总。
 *
 * <p>由 {@link IntegratedPackingApplication} 在设置 JVM 属性
 * {@code qlearning.experiment.trainRounds} 后调用。所有轮次共用一个模型目录，
 * 但每个阶段和训练轮次拥有独立的 NFP、排样及轨迹归档目录。</p>
 */
public final class QExperimentRunner {

    private static final String TRAINING_ROUNDS_PROPERTY = "qlearning.experiment.trainRounds";
    private static final String EXPERIMENT_DIRECTORY_PROPERTY = "qlearning.experiment.directory";
    private static final String MODEL_DIRECTORY_PROPERTY = "qlearning.modelDirectory";
    private static final String TABLE_FILE_NAME = "packing-q-tables.properties";
    private static final String TRACE_FILE_NAME = "packing-q-trace.csv";

    private QExperimentRunner() {
    }

    /**
     * 从 JVM 属性读取连续训练轮数。
     *
     * @return {@code qlearning.experiment.trainRounds} 的正整数值；未设置或非法时返回 0。
     */
    public static int trainingRoundsFromSystemProperties() {
        try {
            return Math.max(0, Integer.parseInt(System.getProperty(TRAINING_ROUNDS_PROPERTY, "0")));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * 自动执行一次基线、若干轮训练和一次贪婪评估，并写出统一汇总 CSV。
     *
     * @param casePath 单个案例 JSON 文件，或包含多个案例 JSON 文件的目录。
     * @param trainingRounds 训练轮数，必须为正数。
     * @throws IOException 当输入、阶段输出、模型目录或汇总文件无法读写时抛出。
     */
    public static void run(Path casePath, int trainingRounds) throws IOException {
        if (trainingRounds <= 0) {
            throw new IllegalArgumentException("trainingRounds 必须为正数: " + trainingRounds);
        }

        Path experimentDirectory = resolveExperimentDirectory(casePath);
        Path modelDirectory = resolveModelDirectory(experimentDirectory);
        QLearningConfig configured = QLearningConfig.fromSystemProperties();
        List<SummaryRow> summaryRows = new ArrayList<>();

        System.out.println("开始自动 Q-learning 实验: " + experimentDirectory.toAbsolutePath());
        runStage(casePath, experimentDirectory.resolve("baseline"), configured.withMode(QMode.OFF),
                modelDirectory, "baseline", 0, summaryRows);

        for (int round = 1; round <= trainingRounds; round++) {
            Path stageDirectory = experimentDirectory.resolve("training")
                    .resolve(String.format(Locale.ROOT, "round-%02d", round));
            runStage(casePath, stageDirectory, configured.withMode(QMode.TRAIN),
                    modelDirectory, "train", round, summaryRows);
            archiveTrace(modelDirectory, experimentDirectory.resolve("traces"), round);
        }

        runStage(casePath, experimentDirectory.resolve("evaluation"), configured.withMode(QMode.EVALUATE),
                modelDirectory, "evaluate", 0, summaryRows);
        writeSummary(experimentDirectory.resolve("experiment-summary.csv"), summaryRows);
        System.out.println("自动实验完成，汇总文件: "
                + experimentDirectory.resolve("experiment-summary.csv").toAbsolutePath());
    }

    /**
     * 执行一个实验阶段，并从该阶段全部案例的 total.txt 汇总指标。
     *
     * @param casePath 当前实验使用的案例路径。
     * @param stageDirectory 当前阶段的独立输出目录。
     * @param config 当前阶段的 Q-learning 运行配置。
     * @param modelDirectory 所有训练与评估阶段共享的 Q 表目录。
     * @param stageName 写入汇总表的阶段名称。
     * @param round 当前训练轮次；基线和评估使用 0。
     * @param summaryRows 用于累积全部阶段结果的可变列表。
     * @throws IOException 当阶段执行或指标读取失败时抛出。
     */
    private static void runStage(Path casePath,
                                 Path stageDirectory,
                                 QLearningConfig config,
                                 Path modelDirectory,
                                 String stageName,
                                 int round,
                                 List<SummaryRow> summaryRows) throws IOException {
        Path nfpDirectory = stageDirectory.resolve("nfp");
        Path packingDirectory = stageDirectory.resolve("packing");
        System.out.printf(Locale.ROOT, "执行阶段 %s%s%n", stageName,
                round <= 0 ? "" : " round " + round);
        IntegratedPackingApplication.run(casePath, nfpDirectory, packingDirectory, config, modelDirectory);
        summaryRows.addAll(readStageSummary(packingDirectory, stageName, round));
    }

    /**
     * 将当前训练轮次生成的轨迹复制到实验归档目录，避免下一轮训练覆盖它。
     *
     * @param modelDirectory 当前训练使用的共享模型目录。
     * @param traceDirectory 用于保存每轮独立轨迹的实验目录。
     * @param round 已完成的训练轮次。
     * @throws IOException 当轨迹复制失败时抛出。
     */
    private static void archiveTrace(Path modelDirectory, Path traceDirectory, int round) throws IOException {
        Path sourceTrace = modelDirectory.resolve(TRACE_FILE_NAME);
        if (!Files.isRegularFile(sourceTrace)) {
            return;
        }
        Files.createDirectories(traceDirectory);
        Path archivedTrace = traceDirectory.resolve(String.format(Locale.ROOT,
                "training-round-%02d.csv", round));
        Files.copy(sourceTrace, archivedTrace, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 从一个阶段的案例结果目录读取 total.txt，并转换成可比较的汇总行。
     *
     * @param packingDirectory 当前阶段的排样结果根目录。
     * @param stageName 当前阶段名称。
     * @param round 当前训练轮次；基线和评估使用 0。
     * @return 当前阶段所有已完成案例的指标行。
     * @throws IOException 当结果目录无法遍历或 total.txt 无法读取时抛出。
     */
    private static List<SummaryRow> readStageSummary(Path packingDirectory,
                                                     String stageName,
                                                     int round) throws IOException {
        List<Path> totalFiles;
        try (var stream = Files.walk(packingDirectory, 2)) {
            totalFiles = stream
                    .filter(path -> path.getFileName().toString().equals("total.txt"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        List<SummaryRow> rows = new ArrayList<>();
        for (Path totalFile : totalFiles) {
            int priorityBoards = -1;
            int ordinaryBoards = -1;
            int totalBoards = -1;
            double utilization = Double.NaN;
            double equivalentContainerCount = Double.NaN;
            double actualUtilization = Double.NaN;
            double solveTimeSeconds = Double.NaN;
            for (String line : Files.readAllLines(totalFile, StandardCharsets.UTF_8)) {
                if (line.startsWith("Priority containers (Sp):")) {
                    priorityBoards = parseIntValue(line);
                } else if (line.startsWith("Ordinary containers (So):")) {
                    ordinaryBoards = parseIntValue(line);
                } else if (line.startsWith("S = Sp + So:")) {
                    totalBoards = parseIntValue(line);
                } else if (line.startsWith("Average utilization rate of this batch:")) {
                    utilization = parseDoubleValue(line);
                } else if (line.startsWith("Equivalent container count (N):")) {
                    equivalentContainerCount = parseDoubleValue(line);
                } else if (line.startsWith("Actual average utilization (Uagv):")) {
                    actualUtilization = parseDoubleValue(line);
                } else if (line.startsWith("Actual solve time:")) {
                    solveTimeSeconds = parseDoubleValue(line);
                }
            }
            String caseName = totalFile.getParent().getFileName().toString();
            rows.add(new SummaryRow(stageName, round, caseName, priorityBoards,
                    ordinaryBoards, totalBoards, utilization, equivalentContainerCount,
                    actualUtilization, solveTimeSeconds));
        }
        return rows;
    }

    /**
     * 将实验指标写为 UTF-8 CSV，便于使用 Excel 或统计脚本比较。
     *
     * @param summaryFile 输出 CSV 路径。
     * @param rows 全部阶段和案例的汇总指标行。
     * @throws IOException 当 CSV 无法创建或写入时抛出。
     */
    private static void writeSummary(Path summaryFile, List<SummaryRow> rows) throws IOException {
        Files.createDirectories(summaryFile.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(summaryFile, StandardCharsets.UTF_8)) {
            writer.write("Stage,Round,Case,Sp,So,S,AverageUtilizationPercent,EquivalentContainerCount,ActualAverageUtilizationPercent,ActualSolveTimeSeconds");
            writer.newLine();
            for (SummaryRow row : rows) {
                writer.write(row.stageName() + "," + row.round() + "," + row.caseName() + ","
                        + row.priorityBoards() + "," + row.ordinaryBoards() + "," + row.totalBoards() + ","
                        + row.utilization() + "," + row.equivalentContainerCount() + ","
                        + row.actualUtilization() + "," + row.solveTimeSeconds());
                writer.newLine();
            }
        }
    }

    /**
     * 解析 total.txt 冒号后的整数指标。
     *
     * @param line 包含一个整数指标的结果文件行。
     * @return 解析后的整数；格式异常时返回 -1。
     */
    private static int parseIntValue(String line) {
        try {
            return Integer.parseInt(valueAfterColon(line).trim());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /**
     * 解析 total.txt 冒号后的百分比或秒数指标。
     *
     * @param line 包含一个浮点指标的结果文件行。
     * @return 解析后的浮点数；格式异常时返回 {@link Double#NaN}。
     */
    private static double parseDoubleValue(String line) {
        try {
            String numericValue = valueAfterColon(line).replace("%", "").replace("s", "").trim();
            return Double.parseDouble(numericValue);
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    /**
     * 返回文本行中第一个冒号之后的部分。
     *
     * @param line 结果文件中的一行文本。
     * @return 冒号后的文本；没有冒号时返回空字符串。
     */
    private static String valueAfterColon(String line) {
        int colonIndex = line.indexOf(':');
        return colonIndex < 0 ? "" : line.substring(colonIndex + 1);
    }

    /**
     * 解析实验输出根目录。
     *
     * @param casePath 当前实验使用的案例文件或目录。
     * @return JVM 属性指定的实验目录；未设置时返回案例目录同级的默认实验目录。
     * @throws IOException 当案例路径不存在或无法确定父目录时抛出。
     */
    private static Path resolveExperimentDirectory(Path casePath) throws IOException {
        String configuredDirectory = System.getProperty(EXPERIMENT_DIRECTORY_PROPERTY);
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            return Path.of(configuredDirectory.trim());
        }
        if (casePath == null || !Files.exists(casePath)) {
            throw new IOException("案例路径不存在: " + casePath);
        }
        Path caseDirectory = Files.isDirectory(casePath) ? casePath : casePath.getParent();
        if (caseDirectory == null) {
            throw new IOException("无法根据案例路径确定实验目录: " + casePath);
        }
        Path parentDirectory = caseDirectory.getParent() == null ? caseDirectory : caseDirectory.getParent();
        return parentDirectory.resolve("qlearningExperiments");
    }

    /**
     * 解析共享 Q 表模型目录。
     *
     * @param experimentDirectory 当前实验输出根目录。
     * @return 显式模型目录；未设置时使用当前实验根目录下的 {@code model} 子目录。
     */
    private static Path resolveModelDirectory(Path experimentDirectory) {
        String configuredDirectory = System.getProperty(MODEL_DIRECTORY_PROPERTY);
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            return Path.of(configuredDirectory.trim());
        }
        return experimentDirectory.resolve("model");
    }

    /**
     * 一条可写入实验汇总 CSV 的案例指标记录。
     *
     * @param stageName 阶段名称：baseline、train 或 evaluate。
     * @param round 训练轮次；基线和评估使用 0。
     * @param caseName 案例名称。
     * @param priorityBoards 优先件板材数 Sp。
     * @param ordinaryBoards 普通件新增板材数 So。
     * @param totalBoards 总板材数 S。
     * @param utilization 原有近似矩形面积的平均利用率百分比。
     * @param equivalentContainerCount 等效容器数量 N；值越小表示容器使用效果越好。
     * @param actualUtilization 使用真实多边形面积计算的平均利用率 Uagv 百分比。
     * @param solveTimeSeconds 实际求解时间，单位为秒。
     */
    private record SummaryRow(String stageName,
                              int round,
                              String caseName,
                              int priorityBoards,
                              int ordinaryBoards,
                              int totalBoards,
                              double utilization,
                              double equivalentContainerCount,
                              double actualUtilization,
                              double solveTimeSeconds) {
    }
}
