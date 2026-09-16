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
 * 但每个阶段和训练轮次拥有独立的 NFP、排样及轨迹归档目录。默认每轮训练都会
 * 使用冻结的当轮 Q 表执行 validation，并按 {@code Sp -> Nb -> N -> Uagv -> 时间}
 * 选择冠军表；可用 {@code -Dqlearning.experiment.evaluateEveryRound=false} 关闭。
 * 若要开始独立实验，可用 {@code -Dqlearning.experiment.resetModel=true} 清除指定
 * 模型目录中的旧 Q 表与轨迹。</p>
 */
public final class QExperimentRunner {

    private static final String TRAINING_ROUNDS_PROPERTY = "qlearning.experiment.trainRounds";
    private static final String EXPERIMENT_DIRECTORY_PROPERTY = "qlearning.experiment.directory";
    private static final String MODEL_DIRECTORY_PROPERTY = "qlearning.modelDirectory";
    /** 是否在每轮训练后执行一次冻结验证并据此选择冠军 Q 表。 */
    private static final String EVALUATE_EVERY_ROUND_PROPERTY = "qlearning.experiment.evaluateEveryRound";
    /** 是否在实验开始前清空指定模型目录中的旧 Q 表和轨迹。 */
    private static final String RESET_MODEL_PROPERTY = "qlearning.experiment.resetModel";
    private static final String TABLE_FILE_NAME = "packing-q-tables.properties";
    private static final String TRACE_FILE_NAME = "packing-q-trace.csv";
    private static final String CHECKPOINT_DIRECTORY_NAME = "model-checkpoints";
    private static final String CHAMPION_MODEL_DIRECTORY_NAME = "champion-model";

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
        boolean evaluateEveryRound = Boolean.parseBoolean(
                System.getProperty(EVALUATE_EVERY_ROUND_PROPERTY, "true"));
        if (Boolean.parseBoolean(System.getProperty(RESET_MODEL_PROPERTY, "false"))) {
            resetModelFiles(modelDirectory);
        }

        System.out.println("开始自动 Q-learning 实验: " + experimentDirectory.toAbsolutePath());
        summaryRows.addAll(runStage(casePath, experimentDirectory.resolve("baseline"), configured.withMode(QMode.OFF),
                modelDirectory, "baseline", 0));

        Path checkpointRoot = experimentDirectory.resolve(CHECKPOINT_DIRECTORY_NAME);
        Path championModelDirectory = experimentDirectory.resolve(CHAMPION_MODEL_DIRECTORY_NAME);
        ChampionScore championScore = null;
        int championRound = 0;

        for (int round = 1; round <= trainingRounds; round++) {
            Path stageDirectory = experimentDirectory.resolve("training")
                    .resolve(String.format(Locale.ROOT, "round-%02d", round));
            summaryRows.addAll(runStage(casePath, stageDirectory, configured.withMode(QMode.TRAIN),
                    modelDirectory, "train", round));
            archiveTrace(modelDirectory, experimentDirectory.resolve("traces"), round);

            Path checkpointDirectory = checkpointRoot.resolve(String.format(Locale.ROOT, "round-%02d", round));
            copyModelTable(modelDirectory, checkpointDirectory);
            if (evaluateEveryRound) {
                Path validationDirectory = experimentDirectory.resolve("validation")
                        .resolve(String.format(Locale.ROOT, "round-%02d", round));
                List<SummaryRow> validationRows = runStage(casePath, validationDirectory,
                        configured.withMode(QMode.EVALUATE), checkpointDirectory, "validation", round);
                summaryRows.addAll(validationRows);
                ChampionScore score = ChampionScore.from(validationRows);
                if (championScore == null || score.isBetterThan(championScore)) {
                    championScore = score;
                    championRound = round;
                    copyModelTable(checkpointDirectory, championModelDirectory);
                    System.out.println("  更新冠军 Q 表: round " + round + " (" + score + ")");
                }
            }
        }

        // 关闭逐轮验证时没有可比较的冠军，保留最后一轮表作为兼容回退。
        if (championRound == 0) {
            championRound = trainingRounds;
            copyModelTable(modelDirectory, championModelDirectory);
        }
        // 对外暴露的模型目录始终保存冠军，以便后续手动 EVALUATE 自动使用最佳表。
        copyModelTable(championModelDirectory, modelDirectory);
        summaryRows.addAll(runStage(casePath, experimentDirectory.resolve("evaluation"), configured.withMode(QMode.EVALUATE),
                championModelDirectory, "evaluate", 0));
        writeChampionMetadata(experimentDirectory, championRound, championScore, evaluateEveryRound);
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
     * @return 当前阶段的全部案例汇总行。
     * @throws IOException 当阶段执行或指标读取失败时抛出。
     */
    private static List<SummaryRow> runStage(Path casePath,
                                             Path stageDirectory,
                                             QLearningConfig config,
                                             Path modelDirectory,
                                             String stageName,
                                             int round) throws IOException {
        Path nfpDirectory = stageDirectory.resolve("nfp");
        Path packingDirectory = stageDirectory.resolve("packing");
        System.out.printf(Locale.ROOT, "执行阶段 %s%s%n", stageName,
                round <= 0 ? "" : " round " + round);
        IntegratedPackingApplication.run(casePath, nfpDirectory, packingDirectory, config, modelDirectory);
        return readStageSummary(packingDirectory, stageName, round);
    }

    /**
     * 清除指定模型目录中上一批实验遗留的 Q 表和轨迹，不删除目录中的其他文件。
     *
     * @param modelDirectory 由 {@code qlearning.modelDirectory} 指定的模型目录。
     * @throws IOException 当旧模型文件无法删除时抛出。
     */
    private static void resetModelFiles(Path modelDirectory) throws IOException {
        Files.createDirectories(modelDirectory);
        Files.deleteIfExists(modelDirectory.resolve(TABLE_FILE_NAME));
        Files.deleteIfExists(modelDirectory.resolve(TRACE_FILE_NAME));
        System.out.println("已清空旧 Q 表: " + modelDirectory.toAbsolutePath());
    }

    /**
     * 将一个冻结的 Q 表复制到独立目录，避免后续训练覆盖该轮策略。
     *
     * @param sourceDirectory 当前 Q 表所在目录。
     * @param targetDirectory 用于保存该轮检查点或冠军表的目录。
     * @throws IOException 当源 Q 表不存在或无法复制时抛出。
     */
    private static void copyModelTable(Path sourceDirectory, Path targetDirectory) throws IOException {
        Path source = sourceDirectory.resolve(TABLE_FILE_NAME);
        if (!Files.isRegularFile(source)) {
            throw new IOException("Q 表不存在，无法创建检查点: " + source.toAbsolutePath());
        }
        Files.createDirectories(targetDirectory);
        Files.copy(source, targetDirectory.resolve(TABLE_FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 写出本次实验最终采用的冠军模型信息，方便手动 evaluate 时核对模型来源。
     *
     * @param experimentDirectory 当前实验根目录。
     * @param championRound 冠军模型来源的训练轮次。
     * @param championScore 冠军验证指标；未逐轮验证时为 {@code null}。
     * @param evaluateEveryRound 是否实际执行了逐轮冻结验证。
     * @throws IOException 当说明文件无法写入时抛出。
     */
    private static void writeChampionMetadata(Path experimentDirectory,
                                              int championRound,
                                              ChampionScore championScore,
                                              boolean evaluateEveryRound) throws IOException {
        Path metadataFile = experimentDirectory.resolve("champion-model.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(metadataFile, StandardCharsets.UTF_8)) {
            writer.write("Champion round: " + championRound);
            writer.newLine();
            writer.write("Validation enabled: " + evaluateEveryRound);
            writer.newLine();
            writer.write("Objective order: total Sp -> total Nb -> total N -> higher total Uagv -> lower solve time");
            writer.newLine();
            if (championScore != null) {
                writer.write("Champion metrics: " + championScore);
                writer.newLine();
            }
        }
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
            double totalPackingTimeSeconds = Double.NaN;
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
                } else if (line.startsWith("Actual optimization time:")) {
                    solveTimeSeconds = parseDoubleValue(line);
                } else if (line.startsWith("Total packing time (including ordinary insertion):")) {
                    totalPackingTimeSeconds = parseDoubleValue(line);
                }
            }
            String caseName = totalFile.getParent().getFileName().toString();
            rows.add(new SummaryRow(stageName, round, caseName, priorityBoards,
                    ordinaryBoards, totalBoards, utilization, equivalentContainerCount,
                    actualUtilization, solveTimeSeconds, totalPackingTimeSeconds));
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
            writer.write("Stage,Round,Case,Sp,So,S,AverageUtilizationPercent,EquivalentContainerCount,ActualAverageUtilizationPercent,ActualOptimizationTimeSeconds,TotalPackingTimeSeconds");
            writer.newLine();
            for (SummaryRow row : rows) {
                writer.write(row.stageName() + "," + row.round() + "," + row.caseName() + ","
                        + row.priorityBoards() + "," + row.ordinaryBoards() + "," + row.totalBoards() + ","
                        + row.utilization() + "," + row.equivalentContainerCount() + ","
                        + row.actualUtilization() + "," + row.solveTimeSeconds() + ","
                        + row.totalPackingTimeSeconds());
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
     * @param solveTimeSeconds 受 600 秒预算限制的全局重排优化时间，单位为秒。
     * @param totalPackingTimeSeconds 包含普通件插入的总排样墙钟时间，单位为秒。
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
                              double solveTimeSeconds,
                              double totalPackingTimeSeconds) {
    }

    /**
     * 一轮冻结验证在全部案例上的聚合目标，用于选择不会被后续训练覆盖的冠军 Q 表。
     *
     * <p>比较顺序严格遵循项目目标：总 Sp 越小越好；总实际容器数 Nb 越小越好；
     * 当前两项相同时总 N 越小越好；随后总 Uagv 越大越好；最后才比较总求解时间。</p>
     *
     * @param priorityBoards 全部案例优先容器数 Sp 之和。
     * @param totalBoards 全部案例实际容器数 Nb 之和。
     * @param equivalentContainers 全部案例等效容器数 N 之和。
     * @param actualUtilization 全部案例真实平均利用率 Uagv 之和。
     * @param solveTimeSeconds 全部案例实际求解时间之和，单位为秒。
     */
    private record ChampionScore(int priorityBoards,
                                 int totalBoards,
                                 double equivalentContainers,
                                 double actualUtilization,
                                 double solveTimeSeconds) {

        /**
         * 从一个冻结验证阶段的案例汇总行计算可比较的全局目标。
         *
         * @param rows 同一轮 validation 阶段中全部案例的结果行。
         * @return 按 Sp、Nb、N、Uagv、时间聚合后的冠军比较指标。
         */
        private static ChampionScore from(List<SummaryRow> rows) {
            int priorityBoards = 0;
            int totalBoards = 0;
            double equivalentContainers = 0.0;
            double actualUtilization = 0.0;
            double solveTimeSeconds = 0.0;
            for (SummaryRow row : rows) {
                priorityBoards += safeNonNegative(row.priorityBoards());
                totalBoards += safeNonNegative(row.totalBoards());
                equivalentContainers += safeFinite(row.equivalentContainerCount(), Double.MAX_VALUE / 1_000.0);
                actualUtilization += safeFinite(row.actualUtilization(), 0.0);
                solveTimeSeconds += safeFinite(row.solveTimeSeconds(), Double.MAX_VALUE / 1_000.0);
            }
            return new ChampionScore(priorityBoards, totalBoards, equivalentContainers,
                    actualUtilization, solveTimeSeconds);
        }

        /**
         * 按项目定义的分层目标判断当前验证结果是否优于既有冠军。
         *
         * @param other 当前已保存的冠军指标。
         * @return 当前指标更优时返回 {@code true}。
         */
        private boolean isBetterThan(ChampionScore other) {
            if (priorityBoards != other.priorityBoards) {
                return priorityBoards < other.priorityBoards;
            }
            if (totalBoards != other.totalBoards) {
                return totalBoards < other.totalBoards;
            }
            int equivalentComparison = Double.compare(equivalentContainers, other.equivalentContainers);
            if (equivalentComparison != 0) {
                return equivalentComparison < 0;
            }
            int utilizationComparison = Double.compare(actualUtilization, other.actualUtilization);
            if (utilizationComparison != 0) {
                return utilizationComparison > 0;
            }
            return Double.compare(solveTimeSeconds, other.solveTimeSeconds) < 0;
        }

        /**
         * 将解析失败的整数指标转换为最小可用值，避免影响聚合运算的稳定性。
         *
         * @param value 待规范化的整数指标。
         * @return 非负整数。
         */
        private static int safeNonNegative(int value) {
            return Math.max(0, value);
        }

        /**
         * 将缺失或非有限浮点指标替换为调用方给定的保守值。
         *
         * @param value 待规范化的浮点指标。
         * @param fallback 指标缺失时使用的回退值。
         * @return 有限的浮点数。
         */
        private static double safeFinite(double value, double fallback) {
            return Double.isFinite(value) ? value : fallback;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "Sp=%d, Nb=%d, N=%.4f, UagvSum=%.4f, Time=%.3fs",
                    priorityBoards, totalBoards, equivalentContainers,
                    actualUtilization, solveTimeSeconds);
        }
    }
}
