package org.example.beamsearch.application;

import org.example.beamsearch.common.ExecutionResult;
import org.example.beamsearch.common.Instance;
import org.example.beamsearch.common.RepackStatistics;
import org.example.beamsearch.common.Solution;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 混合排样的可选收敛记录器，只读取已经提交的完整阶段解。
 * 面积统一使用传入优化的矩形面积（NFP 块外接矩形），不使用原始多边形真实面积。
 *
 * <p>每个案例在当前线程内独立启用，未启用时不创建采样器或输出文件。
 * 搜索中只保存数值，两个阶段都结束后才写 CSV，不改变搜索条件、随机数或提交规则。</p>
 */
public final class PackingConvergenceRecorder implements AutoCloseable {
    public static final String PRIORITY = "priority";
    public static final String ORDINARY = "ordinary";

    private static final ThreadLocal<PackingConvergenceRecorder> ACTIVE = new ThreadLocal<>();
    private static final String HEADER = "sequence,elapsed_ms,event,board_count,total_rectangle_area,board_area,"
            + "min_board_utilization,equivalent_board_count,overall_utilization,score_u_squared,"
            + "best_score_u_squared,attempt_count,partial_transfer_count,board_reduction_count,stop_reason";

    /** 参数集中由混合入口传入；步长单位为毫秒，输出按案例名建立子目录。 */
    public record Config(boolean enabled, long sampleIntervalMs, Path outputRoot) {
        public Config {
            if (enabled && (sampleIntervalMs <= 0 || outputRoot == null)) {
                throw new IllegalArgumentException("启用收敛统计时，采样步长必须大于零且输出目录不能为空。");
            }
        }
    }

    private final Config config;
    private final Path outputDirectory;
    private final LongSupplier clock;
    private final Map<String, Stage> stages = new LinkedHashMap<>();

    private PackingConvergenceRecorder(Config config, Path outputDirectory, LongSupplier clock) {
        this.config = config;
        this.outputDirectory = outputDirectory;
        this.clock = clock;
    }

    /** 关闭开关时直接返回 null；try-with-resources 可安全接受 null。 */
    public static PackingConvergenceRecorder openCase(Config config, String caseName) throws IOException {
        return openCase(config, caseName, System::nanoTime);
    }

    /** 包内时钟入口仅用于无休眠的确定性测试，正常运行使用单调系统时钟。 */
    static PackingConvergenceRecorder openCase(Config config, String caseName, LongSupplier clock) throws IOException {
        if (config == null || !config.enabled()) {
            return null;
        }
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("同一线程不能同时记录多个排样案例。");
        }
        Path directory = config.outputRoot().resolve(caseName);
        // 提前创建输出目录；文件本身延迟到整个案例结束后写出。
        Files.createDirectories(directory);
        PackingConvergenceRecorder recorder = new PackingConvergenceRecorder(config, directory, clock);
        ACTIVE.set(recorder);
        return recorder;
    }

    /** 仅显式命名的混排阶段可以采样，其他入口的原有调用仍返回 null。 */
    static Stage startStage(String name, Instance instance, ExecutionResult result) {
        PackingConvergenceRecorder recorder = ACTIVE.get();
        if (recorder == null || name == null) {
            return null;
        }
        if (!PRIORITY.equals(name) && !ORDINARY.equals(name)) {
            throw new IllegalArgumentException("未知的收敛统计阶段: " + name);
        }
        if (recorder.stages.containsKey(name)) {
            throw new IllegalStateException("当前案例已记录该优化阶段: " + name);
        }
        Stage stage = new Stage(instance, result, recorder.config.sampleIntervalMs(), recorder.clock);
        recorder.stages.put(name, stage);
        return stage;
    }

    /** 案例求解退出后统一落盘，并始终清理线程状态，避免串到下一个案例。 */
    @Override
    public void close() throws IOException {
        try {
            writeStage(PRIORITY, "no_priority_workpieces");
            writeStage(ORDINARY, "no_remaining_ordinary_workpieces");
        } finally {
            ACTIVE.remove();
        }
    }

    private void writeStage(String name, String missingReason) throws IOException {
        Path file = outputDirectory.resolve(name + "_optimization.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.newLine();
            Stage stage = stages.get(name);
            if (stage == null) {
                // 阶段不存在时评分留空，绘图程序可以明确跳过，而不是误画为零分。
                writer.write(String.join(",", "0", "0", "skipped", "0", "", "", "", "", "", "", "",
                        "0", "0", "0", missingReason));
                writer.newLine();
            } else {
                for (int i = 0; i < stage.rows.size(); i++) {
                    writer.write(stage.rows.get(i).toCsv(i, stage.totalArea, stage.boardArea));
                    writer.newLine();
                }
            }
        }
    }

    /** 一个独立优化阶段的轻量采样器；不持有可变的解或工件引用。 */
    public static final class Stage {
        private final long intervalNanos;
        private final LongSupplier clock;
        private final long startNanos;
        private final double totalArea;
        private final double boardArea;
        private final List<Row> rows = new ArrayList<>();
        private long lastSampleNanos;
        private Metrics current;
        private double bestScore;

        // 可注入单调时钟，测试采样间隔时无需休眠，也不依赖机器速度。
        Stage(Instance instance, ExecutionResult result, long intervalMs, LongSupplier clock) {
            this.intervalNanos = intervalMs > Long.MAX_VALUE / 1_000_000L
                    ? Long.MAX_VALUE : intervalMs * 1_000_000L;
            this.clock = clock;
            // BeamSearch 长度放大十倍，面积除以 100 后统一为输入的平方毫米。
            this.boardArea = (double) instance.length * instance.width / 100.0;
            // 分子固定为传入本阶段的全部矩形面积，包含暂时未排入接收板的物品。
            this.totalArea = instance.totalBoxVolume / 100.0;
            this.current = measure(result);
            this.bestScore = current.score();
            this.startNanos = clock.getAsLong();
            this.lastSampleNanos = startNanos;
            record(startNanos, "initial", null, "");
        }

        /**
         * 部分搬运或减板的完整解提交后调用。每次提交更新评分，但只在到达步长、
         * 刷新历史最优或成功减板时保存行，兼顾文件大小和关键改进的完整性。
         */
        public void committed(ExecutionResult result, RepackStatistics statistics, boolean reduced) {
            current = measure(result);
            boolean improved = current.score() > bestScore;
            bestScore = Math.max(bestScore, current.score());
            long now = clock.getAsLong();
            if (reduced || improved || now - lastSampleNanos >= intervalNanos) {
                record(now, reduced ? "board_reduction" : "partial_transfer", statistics, "");
            }
        }

        /** 在局部尝试结束的安全位置采样，使用最近已提交解的缓存指标，避免反复扫描布局。 */
        public void sampleIfDue(RepackStatistics statistics) {
            long now = clock.getAsLong();
            if (now - lastSampleNanos >= intervalNanos) {
                record(now, "sample", statistics, "");
            }
        }

        /** 保留实际返回解和实际结束时间，使最后一次改善后的平台能一直画到优化结束。 */
        void finish(ExecutionResult result, RepackStatistics statistics) {
            current = measure(result);
            bestScore = Math.max(bestScore, current.score());
            record(clock.getAsLong(), "end", statistics, statistics.stopReason);
        }

        private void record(long now, String event, RepackStatistics statistics, String reason) {
            rows.add(new Row(Math.max(0.0, (now - startNanos) / 1_000_000.0), event, current, bestScore,
                    statistics == null ? 0 : statistics.pairRepackAttempts + statistics.tripleRepackAttempts,
                    statistics == null ? 0 : statistics.committedPartialTransfers,
                    statistics == null ? 0 : statistics.boardReductions, reason));
            lastSampleNanos = now;
        }

        /** 所有利用率都使用矩形占用面积；最低矩形利用率板作为计算上的末板。 */
        private Metrics measure(ExecutionResult result) {
            int count = result.solutions.size();
            double minimum = Double.POSITIVE_INFINITY;
            for (Solution board : result.solutions) {
                minimum = Math.min(minimum, boardArea <= 0.0 ? 0.0 : rectangleArea(board) / boardArea);
            }
            minimum = count == 0 ? 0.0 : minimum;
            double equivalent = count == 0 ? 0.0 : count - 1.0 + minimum;
            double utilization = equivalent <= 0.0 || boardArea <= 0.0
                    ? 0.0 : totalArea / (equivalent * boardArea);
            return new Metrics(count, minimum, equivalent, utilization, utilization * utilization);
        }

        /** 复用 Solution 已维护的矩形面积和，无需逐件扫描或读取真实面积字段。 */
        private static double rectangleArea(Solution board) {
            return Math.max(0.0, board.getBoxesVolume() / 100.0);
        }
    }

    private record Metrics(int boards, double minimum, double equivalent, double utilization, double score) { }

    /** 保存标量快照，后续布局更改不会影响已经采集的历史行。 */
    private record Row(double elapsedMs, String event, Metrics metrics, double bestScore,
                       long attempts, long transfers, long reductions, String reason) {
        String toCsv(int sequence, double totalArea, double boardArea) {
            return sequence + "," + elapsedMs + "," + event + "," + metrics.boards() + "," + totalArea
                    + "," + boardArea + "," + metrics.minimum() + "," + metrics.equivalent()
                    + "," + metrics.utilization() + "," + metrics.score() + "," + bestScore
                    + "," + attempts + "," + transfers + "," + reductions + "," + reason;
        }
    }
}
