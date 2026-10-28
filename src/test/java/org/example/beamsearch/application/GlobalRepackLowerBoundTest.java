package org.example.beamsearch.application;

import org.example.beamsearch.common.Box;
import org.example.beamsearch.common.Container;
import org.example.beamsearch.common.ExecutionResult;
import org.example.beamsearch.common.Instance;
import org.example.beamsearch.common.PlacedCuboid;
import org.example.beamsearch.common.Solution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class GlobalRepackLowerBoundTest {
    @Test
    void skipsSearchAtAMultiBoardLowerBoundAndStillBuildsBoardStates() {
        Instance instance = sixFixedRectangles();
        ExecutionResult result = GlobalRepackOptimizer.optimize(instance, seed(instance, 2, 2, 2), 1000, 3);
        assertEquals(3, result.solutions.size());
        assertEquals(3, result.boardStates.size());
        assertArrayEquals(new int[]{0}, result.unplacedCounts);
        assertFalse(result.repackStatistics.executed);
        assertEquals(0, result.repackStatistics.pairRepackAttempts);
        assertEquals("lower_bound_reached_before_optimization", result.repackStatistics.stopReason);
    }

    @Test
    void stopsImmediatelyAfterReducingFourBoardsToTheThreeBoardLowerBound() {
        Instance instance = sixFixedRectangles();
        ExecutionResult result = GlobalRepackOptimizer.optimize(instance, seed(instance, 2, 2, 1, 1), 2000, 3);
        assertEquals(3, result.solutions.size());
        assertEquals(3, result.boardStates.size());
        assertEquals(6, result.solutions.stream().mapToInt(s -> s.getPlacedCuboid().size()).sum());
        assertArrayEquals(new int[]{0}, result.unplacedCounts);
        assertTrue(result.repackStatistics.executed);
        assertEquals(1, result.repackStatistics.boardReductions);
        assertEquals(0, result.repackStatistics.noImprovementSweeps);
        assertEquals("lower_bound_reached_after_reduction", result.repackStatistics.stopReason);
    }

    @Test
    void legacyOptimizerDoesNotEnableTheLowerBoundEvenWhenAlreadyOptimal() {
        Instance instance = sixFixedRectangles();
        ExecutionResult result = GlobalRepackOptimizer.optimize(instance, seed(instance, 2, 2, 2), 1000);
        assertEquals(3, result.solutions.size());
        assertTrue(result.repackStatistics.executed);
        assertFalse(result.repackStatistics.stopReason.startsWith("lower_bound_reached"));
        assertTrue(result.repackStatistics.pairRepackAttempts > 0);
    }

    @Test
    void unattainedLowerBoundRetainsTheExistingSearchAndBudgetStops() {
        Instance instance = sixFixedRectangles();
        // 面积下界 2 有效但不紧：此固定方向案例实际需要 3 张板。
        ExecutionResult result = GlobalRepackOptimizer.optimize(instance, seed(instance, 2, 2, 2), 1000, 2);
        assertEquals(3, result.solutions.size());
        assertTrue(result.repackStatistics.executed);
        assertTrue(result.repackStatistics.pairRepackAttempts > 0);
        assertFalse(result.repackStatistics.stopReason.startsWith("lower_bound_reached"));
        ExecutionResult noBudget = GlobalRepackOptimizer.optimize(instance, seed(instance, 2, 2, 1, 1), 0, 3);
        assertEquals(4, noBudget.solutions.size());
        assertFalse(noBudget.repackStatistics.executed);
        assertEquals("non_positive_time_limit", noBudget.repackStatistics.stopReason);
    }

    @Test
    void refusesInconsistentBoundsIncompleteSolutionsAndOverlaps() {
        Instance instance = sixFixedRectangles();
        assertThrows(IllegalStateException.class,
                () -> GlobalRepackOptimizer.optimize(instance, seed(instance, 2, 2, 2), 1000, 4));
        assertThrows(IllegalStateException.class,
                () -> GlobalRepackOptimizer.optimize(instance, seed(instance, 1, 1, 1), 1000, 3));
        ExecutionResult overlap = seed(instance, 2, 2, 2);
        overlap.solutions.get(0).getPlacedCuboid().get(1).y = 0;
        assertThrows(IllegalStateException.class,
                () -> GlobalRepackOptimizer.optimize(instance, overlap, 1000, 3));
    }

    private static Instance sixFixedRectangles() {
        Container container = new Container("1", 10, 10, 0);
        Box box = new Box();
        HashMap<String, Container> containers = new HashMap<>();
        containers.put("1", container);
        box.initializeWitColorFromCsv(Arrays.asList("case", "rectangle", "6", "1", "6", "4", "0", "1"),
                containers, 1);
        return new Instance(new ArrayList<>(java.util.List.of(box)), container);
    }

    private static ExecutionResult seed(Instance instance, int... counts) {
        ExecutionResult result = new ExecutionResult();
        Box box = instance.boxes[0];
        for (int count : counts) {
            Solution board = new Solution(instance);
            for (int i = 0; i < count; i++) {
                board.add(new PlacedCuboid(0, i * 40, 60, 40, box, 0));
            }
            result.solutions.add(board);
        }
        result.solution = result.solutions.get(result.solutions.size() - 1);
        result.setAvgUtilization();
        return result;
    }
}
