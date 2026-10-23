package org.example.beamsearch.algo;

import org.example.beamsearch.common.*;
import org.example.beamsearch.spacemanager.SpaceManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 用确定性局部解隔离邻域调度/事务逻辑，另用真实求解器覆盖三板和截止时间。 */
class AdaptiveRepackTest {
    @Test
    void keepsOneAndTwoReceiverPartialTransfers() {
        for (int receivers : new int[]{1, 2}) {
            Instance instance = instance(6);
            ScriptedSearch search = new ScriptedSearch(instance, receivers, Mode.PARTIAL);
            Run run = run(search, seed(instance, 2, 2, 2), 0);
            assertEquals(3, run.result.solutions.size());
            assertEquals(1, run.statistics.committedPartialTransfers);
            assertEquals(0, run.statistics.neighborhoodExpansions);
            assertEquals(configuredSweepLimit(), run.statistics.noImprovementSweeps);
            assertComplete(run.result, 6);
        }
    }

    @Test
    void rejectsPartialTransferWhenDonorRebuildOverlapsOrLosesAnItem() {
        for (Mode mode : new Mode[]{Mode.INVALID_DONOR, Mode.INCOMPLETE_DONOR}) {
            Instance instance = instance(9);
            ExecutionResult original = seed(instance, 3, 3, 3);
            List<Solution> before = new ArrayList<>(original.solutions);
            ScriptedSearch search = new ScriptedSearch(instance, 2, mode);
            Run run = run(search, original, 0);
            assertEquals(1, run.statistics.rejectedPartialTransfers);
            assertEquals(0, run.statistics.committedPartialTransfers);
            for (int i = 0; i < before.size(); i++) {
                assertSame(before.get(i), run.result.solutions.get(i));
            }
            assertComplete(run.result, 9);
        }
    }

    @Test
    void refusesMalformedReceivingLayoutBeforeRebuildingDonor() {
        Instance instance = instance(6);
        ScriptedSearch search = new ScriptedSearch(instance, 2, Mode.INVALID_RECEIVER);
        Run run = run(search, seed(instance, 2, 2, 2), 0);
        assertEquals(0, run.statistics.sameBoardRebuildAttempts);
        assertEquals(0, run.statistics.committedPartialTransfers);
        assertComplete(run.result, 6);
    }

    @Test
    void smallInitialSolutionNeverExpands() {
        Instance instance = instance(18);
        ScriptedSearch search = new ScriptedSearch(instance, 3, Mode.STALL);
        Run run = run(search, seed(instance, repeated(9, 2)), 0);
        assertEquals(0, run.statistics.tripleRepackAttempts);
        assertEquals(0, run.statistics.neighborhoodExpansions);
        assertEquals(configuredSweepLimit(), run.statistics.noImprovementSweeps);
    }

    @Test
    void expandsAfterTwoSweepsAndKeepsExpandedNeighborhoodAfterPartialProgress() {
        Instance instance = instance(20);
        ScriptedSearch search = new ScriptedSearch(instance, 3, Mode.PARTIAL);
        Run run = run(search, seed(instance, repeated(10, 2)), 0);
        assertEquals(2, search.firstTripleSweep);
        assertEquals(1, run.statistics.neighborhoodExpansions);
        assertEquals(0, run.statistics.neighborhoodResets);
        assertEquals(1, run.statistics.committedPartialTransfers);
        assertEquals(configuredSweepLimit(), run.statistics.noImprovementSweeps);
        assertTrue(run.statistics.tripleRepackAttempts > 1);
        assertTrue(run.log.contains("[Repack neighborhood expanded] receivers: 2 -> 3"));
        assertComplete(run.result, 20);
    }

    @Test
    void triplePartialTransferThenReductionRestoresBaseAndStopsAtLowerBound() {
        Instance instance = instance(36);
        ScriptedSearch search = new ScriptedSearch(instance, 3, Mode.PARTIAL_THEN_REDUCE);
        // 36 个 5x5 正方形在 10x10 板上有有效面积下界 9。
        Run run = run(search, seed(instance, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4), 9);
        assertEquals(9, run.result.solutions.size());
        assertEquals(1, run.statistics.committedPartialTransfers);
        assertEquals(1, run.statistics.boardReductions);
        assertEquals(1, run.statistics.tripleBoardReductions);
        assertEquals(1, run.statistics.neighborhoodResets);
        assertEquals(0, run.statistics.noImprovementSweeps);
        assertEquals("lower_bound_reached_after_reduction", run.statistics.stopReason);
        assertTrue(run.log.contains("[Repack neighborhood restored] receivers: 3 -> 2"));
        assertComplete(run.result, 36);
    }

    @Test
    void eligibilityUsesInitialCountAndBaseSearchResumesAfterReduction() {
        Instance instance = instance(36);
        ScriptedSearch search = new ScriptedSearch(instance, 3, Mode.PARTIAL_THEN_REDUCE);
        Run run = run(search, seed(instance, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4), 0);
        assertEquals(9, run.result.solutions.size());
        assertTrue(search.baseSearchAfterReduction);
        // 当前只剩九板，但本阶段初始十板，继续停滞两轮后仍允许再次扩张。
        assertEquals(2, run.statistics.neighborhoodExpansions);
        assertEquals(1, run.statistics.neighborhoodResets);
        assertComplete(run.result, 36);
    }

    @Test
    void allowsPartialTransferEvenWhenFourBoardAreaExceedsThreeBoards() {
        Instance instance = instance(37);
        ScriptedSearch search = new ScriptedSearch(instance, 3, Mode.OVER_THREE_BOARD_AREA);
        Run run = run(search, seed(instance, 2, 3, 4, 4, 4, 4, 4, 4, 4, 4), 0);
        assertTrue(search.overCapacityPartialAttempt);
        assertEquals(1, run.statistics.committedPartialTransfers);
        assertEquals(10, run.result.solutions.size());
        assertComplete(run.result, 37);
    }

    @Test
    void realThreeBoardSolverPreservesItemsAndHonorsExpiredDeadline() {
        Instance instance = instance(11);
        BeamSearch search = new BeamSearch(manager(instance), instance);
        ExecutionResult packed = search.solveRepackCandidate(instance, 3, false,
                System.currentTimeMillis() + 2000);
        assertEquals(3, packed.solutions.size());
        assertTrue(packed.unplacedBoxes.isEmpty());
        assertComplete(packed, 11);
        long start = System.currentTimeMillis();
        ExecutionResult expired = search.solveRepackCandidate(instance, 3, false, start - 1);
        assertTrue(System.currentTimeMillis() - start < 1000);
        assertEquals(11, expired.unplacedBoxes.size());
        assertEquals(11 * instance.boxes[0].volume, expired.unplacedBoxesVol);
        assertArrayEquals(new int[]{11}, expired.unplacedCounts);
    }

    private enum Mode {
        STALL, PARTIAL, INVALID_DONOR, INCOMPLETE_DONOR, INVALID_RECEIVER,
        PARTIAL_THEN_REDUCE, OVER_THREE_BOARD_AREA
    }

    private static class ScriptedSearch extends BeamSearch {
        final int targetReceivers;
        final Mode mode;
        boolean moved;
        boolean reduced;
        boolean baseSearchAfterReduction;
        boolean overCapacityPartialAttempt;
        int firstTripleSweep = -1;

        ScriptedSearch(Instance instance, int targetReceivers, Mode mode) {
            super(manager(instance), instance);
            this.targetReceivers = targetReceivers;
            this.mode = mode;
        }

        @Override
        ExecutionResult solveRepackCandidate(Instance local, int receivers, boolean decNode, long deadline) {
            if (receivers == 3 && firstTripleSweep < 0) {
                firstTripleSweep = getLastRepackStatistics().noImprovementSweeps;
            }
            if (reduced && receivers <= 2) {
                baseSearchAfterReduction = true;
            }
            if (receivers == targetReceivers && mode != Mode.STALL && !moved
                    && local.totalBoxCount - 1 <= receivers * 4
                    && (mode != Mode.OVER_THREE_BOARD_AREA || local.totalBoxCount == 13)) {
                moved = true;
                if (mode == Mode.OVER_THREE_BOARD_AREA) {
                    overCapacityPartialAttempt = local.totalBoxVolume > 3.0 * local.length * local.width;
                }
                ExecutionResult result = layout(local, receivers,
                        local.totalBoxCount - (mode == Mode.INVALID_DONOR ? 2 : 1));
                if (mode == Mode.INVALID_RECEIVER) {
                    result.solutions.get(0).getPlacedCuboid().get(0).x = -1;
                }
                return result;
            }
            if (receivers == 3 && mode == Mode.PARTIAL_THEN_REDUCE && moved && !reduced
                    && local.totalBoxCount <= receivers * 4) {
                reduced = true;
                return layout(local, receivers, local.totalBoxCount);
            }
            return layout(local, receivers, 0);
        }

        @Override
        ExecutionResult rebuildDonor(Instance local, long deadline) {
            if (mode == Mode.INCOMPLETE_DONOR) {
                return layout(local, 1, 0);
            }
            ExecutionResult result = super.rebuildDonor(local, deadline);
            if (mode == Mode.INVALID_DONOR) {
                result.solutions.get(0).getPlacedCuboid().get(1).x = 0;
            }
            return result;
        }
    }

    private record Run(ExecutionResult result, RepackStatistics statistics, String log) {}

    private static Run run(BeamSearch search, ExecutionResult initial, int lowerBound) {
        PrintStream previous = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            ExecutionResult result = search.ImproveByRepack(initial, 10, new Random(1), lowerBound);
            return new Run(result, search.getLastRepackStatistics(), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previous);
        }
    }

    private static Instance instance(int count) {
        Container container = new Container("1", 10, 10, 0);
        Box box = new Box();
        HashMap<String, Container> containers = new HashMap<>();
        containers.put("1", container);
        box.initializeWitColorFromCsv(Arrays.asList("case", "square", "" + count, "1", "5", "5", "0", "1"),
                containers, 1);
        return new Instance(new ArrayList<>(List.of(box)), container);
    }

    private static SpaceManager manager(Instance instance) {
        return new SpaceManager(SpaceComparator.getSpaceComparator(instance, 1));
    }

    private static int[] repeated(int boards, int count) {
        int[] values = new int[boards];
        Arrays.fill(values, count);
        return values;
    }

    /** 无减板保护是用户可调整参数，回归测试不固定某一次实验的参数值。 */
    private static int configuredSweepLimit() {
        try {
            var field = BeamSearch.class.getDeclaredField("MAX_NO_IMPROVEMENT_SWEEPS");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ExecutionResult seed(Instance instance, int... counts) {
        ExecutionResult result = new ExecutionResult();
        for (int count : counts) {
            Solution board = new Solution(instance);
            for (int i = 0; i < count; i++) {
                board.add(piece(instance.boxes[0], i));
            }
            result.solutions.add(board);
        }
        result.solution = result.solutions.get(result.solutions.size() - 1);
        result.setAvgUtilization();
        return result;
    }

    private static ExecutionResult layout(Instance instance, int boards, int placedCount) {
        ExecutionResult result = new ExecutionResult();
        result.unplacedBoxes = new ArrayList<>();
        for (int b = 0; b < boards; b++) {
            result.solutions.add(new Solution(instance));
        }
        int index = 0;
        for (Box box : instance.boxes) {
            for (int n = 0; n < box.count; n++, index++) {
                if (index < placedCount) {
                    result.solutions.get(index % boards).add(piece(box, index / boards));
                } else {
                    result.unplacedBoxes.add(box);
                    result.unplacedBoxesVol += box.volume;
                }
            }
        }
        return result;
    }

    private static PlacedCuboid piece(Box box, int slot) {
        return new PlacedCuboid(slot % 2 * 50, slot / 2 * 50, 50, 50, box, 0);
    }

    private static void assertComplete(ExecutionResult result, int expectedItems) {
        assertEquals(expectedItems, result.solutions.stream().mapToInt(s -> s.getPlacedCuboid().size()).sum());
        for (Solution board : result.solutions) {
            Set<String> slots = new HashSet<>();
            for (PlacedCuboid piece : board.getPlacedCuboid()) {
                assertEquals(50, piece.length);
                assertEquals(50, piece.width);
                assertTrue(piece.x == 0 || piece.x == 50);
                assertTrue(piece.y == 0 || piece.y == 50);
                assertTrue(slots.add(piece.x + "," + piece.y));
            }
        }
    }
}
