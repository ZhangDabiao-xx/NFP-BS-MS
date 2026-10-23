package org.example.beamsearch.application;

import org.example.beamsearch.common.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.example.beamsearch.application.StageLowerBoundCalculatorTest.box;
import static org.example.beamsearch.application.StageLowerBoundCalculatorTest.instance;

class MixedStageLowerBoundTest {
    @Test
    void reachingPriorityBoundStillRunsInsertionAndUsesBothItemsRotationRules() {
        Box priority = box("priority-block", 2, 6, 10, false);
        Box ordinaryFixed = ordinary(box("ordinary-fixed", 1, 4, 10, false));
        Box ordinaryRotating = ordinary(box("ordinary-rotating", 1, 10, 4, true));
        ExecutionResult result = PriorityFirstPacker.solveWithTotalTime(
                List.of(instance(priority, ordinaryFixed, ordinaryRotating)), 1000);
        assertEquals(2, result.priorityBoardCount);
        assertEquals(0, result.ordinaryBoardCount);
        assertEquals(4, pieceCount(result));
        assertNull(result.rectangleLowerBound);
        assertFalse(result.priorityRepackStatistics.executed);
        assertEquals("lower_bound_reached_before_optimization", result.priorityRepackStatistics.stopReason);
        StageLowerBound bound = result.priorityRepackStatistics.stageLowerBound;
        assertEquals(2, bound.lowerBound());
        assertEquals(2, bound.itemCount());
        assertEquals("no_remaining_ordinary_workpieces", result.ordinaryRepackStatistics.stopReason);
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
    void remainingOrdinaryBoundExcludesItemsAlreadyInsertedInPriorityBoards() {
        Box priority = box("priority-block", 1, 6, 10, false);
        Box inserted = ordinary(box("inserted", 1, 4, 10, false));
        Box remaining = ordinary(box("remaining", 2, 10, 10, true));
        ExecutionResult result = PriorityFirstPacker.solveWithTotalTime(
                List.of(instance(priority, inserted, remaining)), 1000);
        assertEquals(1, result.priorityBoardCount);
        assertEquals(2, result.ordinaryBoardCount);
        assertEquals(4, pieceCount(result));
        StageLowerBound bound = result.ordinaryRepackStatistics.stageLowerBound;
        assertEquals(StageLowerBoundCalculator.REMAINING_ORDINARY, bound.scope());
        assertEquals(2, bound.itemCount());
        assertEquals(2, bound.lowerBound());
        assertFalse(result.ordinaryRepackStatistics.executed);
        assertEquals("lower_bound_reached_before_optimization", result.ordinaryRepackStatistics.stopReason);
        assertTrue(result.unplacedBoxes.isEmpty());
    }

    @Test
    void ordinaryOnlyCasesHaveAnIndependentBoundAndNoPriorityCalculation() {
        Box fixed = ordinary(box("fixed", 2, 10, 10, false));
        ExecutionResult result = PriorityFirstPacker.solveWithTotalTime(List.of(instance(fixed)), 1000);
        assertEquals(0, result.priorityBoardCount);
        assertEquals(2, result.ordinaryBoardCount);
        assertNull(result.priorityRepackStatistics.stageLowerBound);
        assertEquals(StageLowerBoundCalculator.ORDINARY, result.ordinaryRepackStatistics.stageLowerBound.scope());
        assertEquals(2, result.ordinaryRepackStatistics.stageLowerBound.lowerBound());
        assertFalse(result.ordinaryRepackStatistics.executed);
    }

    @Test
    void reductionStopsAtComputedMixedRotationBoundInEitherStage() {
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
            StageLowerBound bound = StageLowerBoundCalculator.calculate(instance, scope);
            assertEquals(2, bound.lowerBound());
            assertEquals(2, bound.rotatableItemCount());
            ExecutionResult result = GlobalRepackOptimizer.optimizeWithStageLowerBound(instance, seed, 1000, bound);
            assertEquals(2, result.solutions.size());
            assertEquals(4, pieceCount(result));
            assertEquals(2, result.boardStates.size());
            assertSame(bound, result.repackStatistics.stageLowerBound);
            assertEquals(2, result.repackStatistics.boardReductions);
            assertEquals("lower_bound_reached_after_reduction", result.repackStatistics.stopReason);
            assertTrue(result.unplacedBoxes.isEmpty());
        }
    }

    private static Box ordinary(Box box) {
        box.color = "0";
        return box;
    }

    private static int pieceCount(ExecutionResult result) {
        return result.solutions.stream().mapToInt(board -> board.getPlacedCuboid().size()).sum();
    }
}
