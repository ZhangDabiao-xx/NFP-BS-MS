package org.example.beamsearch.application;

import org.example.beamsearch.common.Box;
import org.example.beamsearch.common.Container;
import org.example.beamsearch.common.ExecutionResult;
import org.example.beamsearch.common.Instance;
import org.example.beamsearch.common.PlacedCuboid;
import org.example.beamsearch.common.RepackStatistics;
import org.example.beamsearch.common.Solution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖评分口径、步长、关键事件和真实优化接入，不依赖长时间运行的案例。 */
class PackingConvergenceRecorderTest {
    @TempDir
    Path directory;

    @Test
    void disabledRecorderDoesNotCreateFilesOrSampleStages() throws IOException {
        Path root = directory.resolve("disabled");
        assertNull(PackingConvergenceRecorder.openCase(
                new PackingConvergenceRecorder.Config(false, 1000, root), "case"));
        Instance instance = rectangles("1", 6, 4, 6);
        assertNull(PackingConvergenceRecorder.startStage("priority", instance, seed(instance, 2, 2, 2)));
        assertFalse(Files.exists(root));
    }

    @Test
    void usesRectangleAreaKeepsBestScoreAndHonorsTimeStep() throws IOException {
        Instance instance = rectangles("1", 4, 3, 8);
        // 外接矩形为 12，真实多边形面积为 10，确保收敛评分不再使用真实面积。
        instance.boxes[0].actualWorkpieceArea = 10.0;
        ExecutionResult initial = seed(instance, 2, 2, 2, 2);
        AtomicLong clock = new AtomicLong();
        try (PackingConvergenceRecorder ignored = PackingConvergenceRecorder.openCase(
                new PackingConvergenceRecorder.Config(true, 1000, directory), "sampling", clock::get)) {
            PackingConvergenceRecorder.Stage stage =
                    PackingConvergenceRecorder.startStage("priority", instance, initial);
            RepackStatistics statistics = new RepackStatistics();
            clock.set(500_000_000L);
            stage.sampleIfDue(statistics); // 不到一步，不应生成常规采样行。
            clock.set(1_000_000_000L);
            statistics.pairRepackAttempts = 5;
            stage.sampleIfDue(statistics);
            clock.set(1_100_000_000L);
            stage.committed(initial, statistics, false); // 同分且不到步长，不增加行。
            clock.set(1_200_000_000L);
            ExecutionResult improved = seed(instance, 3, 3, 2);
            statistics.boardReductions = 1;
            stage.committed(improved, statistics, true); // 减板事件无条件保留。
            clock.set(1_300_000_000L);
            stage.committed(seed(instance, 4, 3, 1), statistics, false); // 新最优也不受步长限制。
            clock.set(2_500_000_000L);
            statistics.stopReason = "time_limit_reached";
            stage.finish(initial, statistics); // 实际返回解回落，历史最好值仍保留。
        }
        List<Map<String, String>> rows = read("sampling", "priority");
        assertEquals(List.of("initial", "sample", "board_reduction", "partial_transfer", "end"),
                rows.stream().map(row -> row.get("event")).toList());
        assertEquals(96.0, value(rows.get(0), "total_rectangle_area"));
        assertFalse(rows.get(0).containsKey("total_actual_area"));
        assertEquals(100.0, value(rows.get(0), "board_area"));
        assertEquals(0.24, value(rows.get(0), "min_board_utilization"), 1e-12);
        assertEquals(3.24, value(rows.get(0), "equivalent_board_count"), 1e-12);
        assertEquals(96.0 / 324.0, value(rows.get(0), "overall_utilization"), 1e-12);
        assertEquals(Math.pow(96.0 / 324.0, 2), value(rows.get(0), "score_u_squared"), 1e-12);
        assertEquals(1000.0, value(rows.get(1), "elapsed_ms"));
        assertEquals("5", rows.get(1).get("attempt_count"));
        Map<String, String> end = rows.get(rows.size() - 1);
        assertEquals(2500.0, value(end, "elapsed_ms"));
        assertEquals(Math.pow(96.0 / 324.0, 2), value(end, "score_u_squared"), 1e-12);
        assertTrue(value(end, "best_score_u_squared") > value(end, "score_u_squared"));
        assertEquals("time_limit_reached", end.get("stop_reason"));
        assertEquals("skipped", read("sampling", "ordinary").get(0).get("event"));
        // 采集不修改现有指标或消耗输出使用的 ID 队列。
        assertEquals(0.0, initial.actualTotalWorkpieceArea);
        assertEquals(8, instance.boxes[0].ids.get(0).size());
    }

    @Test
    void realRepackRecordsRectangleScoresWithoutChangingActualAreaReport() throws IOException {
        Instance instance = rectangles("1", 6, 4, 6);
        instance.boxes[0].actualWorkpieceArea = 12.0;
        ExecutionResult result;
        try (PackingConvergenceRecorder ignored = PackingConvergenceRecorder.openCase(
                new PackingConvergenceRecorder.Config(true, 1000, directory), "repack")) {
            result = GlobalRepackOptimizer.optimize(instance, seed(instance, 2, 2, 1, 1),
                    2000, 3, PackingConvergenceRecorder.PRIORITY);
            // 求解中仅缓存在内存，不逐条写磁盘。
            assertFalse(Files.exists(directory.resolve("repack/priority_optimization.csv")));
        }
        result.setActualUtilizationMetrics();
        List<Map<String, String>> rows = read("repack", "priority");
        assertEquals("initial", rows.get(0).get("event"));
        assertTrue(rows.stream().anyMatch(row -> "board_reduction".equals(row.get("event"))));
        Map<String, String> end = rows.get(rows.size() - 1);
        assertEquals("3", end.get("board_count"));
        assertEquals("lower_bound_reached_after_reduction", end.get("stop_reason"));
        // 三张板各放两个 6x4 矩形：最低矩形利用率 0.48，等效板数 2.48。
        assertEquals(144.0 / 248.0, value(end, "overall_utilization"), 1e-12);
        assertEquals(Math.pow(144.0 / 248.0, 2), value(end, "score_u_squared"), 1e-12);
        assertTrue(rows.stream().allMatch(row -> value(row, "total_rectangle_area") == 144.0));
        // 最终结果的真实面积指标仍按每件 12 计算，不受 CSV 口径调整影响。
        assertEquals(72.0, result.actualTotalWorkpieceArea, 1e-12);
        assertEquals(72.0 / 224.0, result.actualAverageUtilization, 1e-12);
    }

    @Test
    void mixedStagesStaySeparateAndLegacyOptimizerDoesNotOptIn() throws IOException {
        Instance priority = rectangles("1", 10, 10, 1);
        Instance ordinary = rectangles("0", 10, 10, 2);
        try (PackingConvergenceRecorder ignored = PackingConvergenceRecorder.openCase(
                new PackingConvergenceRecorder.Config(true, 1000, directory), "mixed")) {
            ExecutionResult result = PriorityFirstPacker.solveWithTotalTime(List.of(priority, ordinary), 1000);
            assertEquals(1, result.priorityBoardCount);
            assertEquals(2, result.ordinaryBoardCount);
        }
        assertEquals(100.0, value(read("mixed", "priority").get(0), "total_rectangle_area"));
        assertEquals(200.0, value(read("mixed", "ordinary").get(0), "total_rectangle_area"));
        Instance instance = rectangles("1", 6, 4, 6);
        try (PackingConvergenceRecorder ignored = PackingConvergenceRecorder.openCase(
                new PackingConvergenceRecorder.Config(true, 1000, directory), "legacy")) {
            GlobalRepackOptimizer.optimize(instance, seed(instance, 2, 2, 2), 1000, 3);
        }
        assertEquals("skipped", read("legacy", "priority").get(0).get("event"));
        assertEquals("", read("legacy", "priority").get(0).get("score_u_squared"));
    }

    private List<Map<String, String>> read(String caseName, String stage) throws IOException {
        List<String> lines = Files.readAllLines(directory.resolve(caseName).resolve(stage + "_optimization.csv"));
        String[] header = lines.get(0).split(",", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] values = line.split(",", -1);
            assertEquals(header.length, values.length);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) {
                row.put(header[i], values[i]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static double value(Map<String, String> row, String column) {
        return Double.parseDouble(row.get(column));
    }

    private static Instance rectangles(String color, int length, int width, int count) {
        Container container = new Container(color, 10, 10, 0);
        HashMap<String, Container> containers = new HashMap<>();
        containers.put(color, container);
        Box box = new Box();
        box.initializeWitColorFromCsv(Arrays.asList("case", "rectangle", Integer.toString(count), color,
                Integer.toString(length), Integer.toString(width), "0", "1"), containers, 1);
        for (int i = 0; i < count; i++) {
            box.ids.get(0).add("id-" + i);
        }
        return new Instance(new ArrayList<>(List.of(box)), container);
    }

    private static ExecutionResult seed(Instance instance, int... counts) {
        ExecutionResult result = new ExecutionResult();
        for (int count : counts) {
            Solution board = new Solution(instance);
            int length = instance.boxes[0].variation[0][1];
            int width = instance.boxes[0].variation[0][0];
            int columns = instance.length / length;
            for (int i = 0; i < count; i++) {
                board.add(new PlacedCuboid(i % columns * length, i / columns * width,
                        length, width, instance.boxes[0], 0));
            }
            result.solutions.add(board);
        }
        result.solution = result.solutions.get(result.solutions.size() - 1);
        result.setAvgUtilization();
        return result;
    }
}
