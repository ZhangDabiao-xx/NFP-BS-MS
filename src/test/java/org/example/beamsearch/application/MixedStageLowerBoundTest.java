package org.example.beamsearch.application;

import org.example.beamsearch.common.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.example.beamsearch.application.StageLowerBoundCalculatorTest.box;
import static org.example.beamsearch.application.StageLowerBoundCalculatorTest.instance;

class MixedStageLowerBoundTest {
    @Test
    void priorityOptimizationRunsAtTheAreaBoundAndPreservesRotationRules() {
        // 优先件已经占满两板，仍应进入优化；普通件独立排样以隔离插入预算设置。
        Box priority = box("priority-block", 2, 10, 10, false);
        Box ordinaryFixed = ordinary(box("ordinary-fixed", 1, 4, 10, false));
        Box ordinaryRotating = ordinary(box("ordinary-rotating", 1, 10, 4, true));
        ExecutionResult result = PriorityFirstPacker.solveWithTotalTime(
                List.of(instance(priority, ordinaryFixed, ordinaryRotating)), 1000);
        assertEquals(2, result.priorityBoardCount);
        assertEquals(1, result.ordinaryBoardCount);
        assertEquals(4, pieceCount(result));
        assertNull(result.rectangleLowerBound);
        assertOptimizationWithoutBound(result.priorityRepackStatistics);
        assertEquals("insufficient_boards", result.ordinaryRepackStatistics.stopReason);
        assertNull(result.ordinaryRepackStatistics.stageLowerBound);
        for (Solution board : result.solutions) {
            for (PlacedCuboid piece : board.getPlacedCuboid()) {
                if (piece.box == ordinaryFixed) {
                    assertEquals(40, piece.length);
                    assertEquals(100, piece.width);
                } else if (piece.box == ordinaryRotating) {
                    assertEquals(40, piece.length);
                    assertEquals(100, piece.width);
                }
            }
        }
        assertTrue(result.unplacedBoxes.isEmpty());
    }

    @Test
    void remainingOrdinaryOptimizationRunsEvenWhenItsAreaBoundIsReached() {
        Box priority = box("priority-block", 1, 10, 10, false);
        Box remaining = ordinary(box("remaining", 2, 10, 10, true));
        ExecutionResult result = PriorityFirstPacker.solveWithTotalTime(
                List.of(instance(priority, remaining)), 1000);
        assertEquals(1, result.priorityBoardCount);
        assertEquals(2, result.ordinaryBoardCount);
        assertEquals(3, pieceCount(result));
        assertNull(result.priorityRepackStatistics.stageLowerBound);
        assertOptimizationWithoutBound(result.ordinaryRepackStatistics);
        assertTrue(result.unplacedBoxes.isEmpty());
    }

    @Test
    void ordinaryOnlyMixedCasesAlsoOptimizeWithoutALowerBound() {
        Box fixed = ordinary(box("fixed", 2, 10, 10, false));
        ExecutionResult result = PriorityFirstPacker.solveWithTotalTime(List.of(instance(fixed)), 1000);
        assertEquals(0, result.priorityBoardCount);
        assertEquals(2, result.ordinaryBoardCount);
        assertNull(result.priorityRepackStatistics.stageLowerBound);
        assertOptimizationWithoutBound(result.ordinaryRepackStatistics);
        assertEquals(2, pieceCount(result));
        assertTrue(result.unplacedBoxes.isEmpty());
    }

    @Test
    void unboundedOptimizationContinuesAfterReductionInEitherStage() {
        for (String scope : List.of(StageLowerBoundCalculator.PRIORITY, StageLowerBoundCalculator.REMAINING_ORDINARY)) {
            Box fixed = box("fixed", 2, 6, 6, false);
            Box rotating = box("rotating", 2, 6, 4, true);
            if (scope.equals(StageLowerBoundCalculator.REMAINING_ORDINARY)) {
                ordinary(fixed);
                ordinary(rotating);
            }
            Instance instance = instance(fixed, rotating);
            ExecutionResult seed = new ExecutionResult();
            for (Box item : List.of(fixed, fixed, rotating, rotating)) {
                Solution board = new Solution(instance);
                board.add(new PlacedCuboid(0, 0, item.variation[0][1], item.variation[0][0], item, 0));
                seed.solutions.add(board);
            }
            seed.solution = seed.solutions.get(3);
            seed.setAvgUtilization();
            // 与混排入口一致，不传下界；减到两板后仍继续扫描而不是按下界退出。
            ExecutionResult result = GlobalRepackOptimizer.optimize(instance, seed, 1000);
            assertEquals(2, result.solutions.size());
            assertEquals(4, pieceCount(result));
            assertEquals(2, result.boardStates.size());
            assertOptimizationWithoutBound(result.repackStatistics);
            assertEquals(2, result.repackStatistics.boardReductions);
            assertTrue(result.repackStatistics.noImprovementSweeps > 0);
            assertTrue(result.unplacedBoxes.isEmpty());
        }
    }

    /** 即使板数已最优，混排仍应尝试重排，且不产生阶段下界元数据。 */
    private static void assertOptimizationWithoutBound(RepackStatistics statistics) {
        assertTrue(statistics.executed);
        assertNull(statistics.stageLowerBound);
        assertFalse(statistics.stopReason.startsWith("lower_bound_reached"));
        assertTrue(statistics.pairRepackAttempts > 0);
    }

    private static Box ordinary(Box box) {
        box.color = "0";
        return box;
    }

    private static int pieceCount(ExecutionResult result) {
        return result.solutions.stream().mapToInt(board -> board.getPlacedCuboid().size()).sum();
    }
}
