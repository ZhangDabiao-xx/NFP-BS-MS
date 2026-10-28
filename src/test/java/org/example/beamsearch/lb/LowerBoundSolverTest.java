package org.example.beamsearch.lb;

import org.example.beamsearch.lb.entity.Item;
import org.example.beamsearch.lb.solver.BM_LowerBound_Solver;
import org.example.beamsearch.lb.solver.CCM_LowerBound_Solver;
import org.example.beamsearch.lb.solver.RectangleLowerBoundSolver;
import org.example.beamsearch.lb.util.DffUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class LowerBoundSolverTest {
    @TempDir
    Path temporary;

    private static Item item(int w, int h) {
        return Item.fromScaledUnits("rectangle", w, h);
    }

    @Test
    void fixesOddCapacityExactTilingAndIntegerOverflow() {
        Item[] tiling = {item(2, 2), item(2, 2), item(1, 2)};
        assertEquals(1, CCM_LowerBound_Solver.LB_CCM_1(5, 2, tiling));
        assertEquals(1, CCM_LowerBound_Solver.LB_CCM_2(5, 2, tiling));
        assertEquals(1, BM_LowerBound_Solver.LB_BM_3(5, 2, tiling));
        assertEquals(1, CCM_LowerBound_Solver.LB_CCM_2(50_000, 50_000,
                new Item[]{item(40_001, 40_000)}));
        Item[] enormous = {item(Integer.MAX_VALUE, Integer.MAX_VALUE - 1),
                item(Integer.MAX_VALUE, Integer.MAX_VALUE - 1),
                item(Integer.MAX_VALUE, Integer.MAX_VALUE - 1)};
        assertEquals(3, RectangleLowerBoundSolver.solve(Integer.MAX_VALUE, Integer.MAX_VALUE - 1,
                enormous, false));
        Item[] largeCoprime = {item(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 2),
                item(Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 3),
                item(Integer.MAX_VALUE - 3, Integer.MAX_VALUE - 4), item(1, 1)};
        assertEquals(3, RectangleLowerBoundSolver.solve(Integer.MAX_VALUE, Integer.MAX_VALUE - 1,
                largeCoprime, true));
    }

    @Test
    void distinguishesFixedAndRotatableRectangles() {
        Item[] items = {item(6, 4), item(6, 4), item(6, 4), item(6, 4), item(6, 4)};
        assertEquals(3, RectangleLowerBoundSolver.solve(10, 10, items, false));
        assertEquals(2, RectangleLowerBoundSolver.solve(10, 10, items, true));
        assertNull(RectangleLowerBoundSolver.solveDetailed(10, 10, items, true, true).bmBound());
        assertNull(RectangleLowerBoundSolver.solveDetailed(10, 10, items, true, true).ccm1Bound());
        for (Item item : items) {
            item.rotatable = false;
        }
        assertEquals(3, RectangleLowerBoundSolver.solve(10, 10, items, true));
    }

    @Test
    void handlesForcedOrientationEmptyInstancesAndInvalidInputs() {
        Item forced = item(3, 5);
        assertEquals(1, RectangleLowerBoundSolver.solve(5, 3, new Item[]{forced}, true));
        assertEquals(3, forced.w, "solver must not mutate caller dimensions");
        assertThrows(IllegalArgumentException.class,
                () -> RectangleLowerBoundSolver.solve(5, 3, new Item[]{forced}, false));
        assertThrows(IllegalArgumentException.class,
                () -> RectangleLowerBoundSolver.solve(2, 2, new Item[]{forced}, true));
        assertEquals(0, RectangleLowerBoundSolver.solve(1, 1, new Item[0], false));
        assertEquals(0, CCM_LowerBound_Solver.LB_CCM_1(1, 1, new Item[0]));
        assertEquals(0, BM_LowerBound_Solver.LB_BM_3(1, 1, new Item[0]));
        assertEquals(3, RectangleLowerBoundSolver.solve(1, 1,
                new Item[]{item(1, 1), item(1, 1), item(1, 1)}, true));
        assertThrows(IllegalArgumentException.class,
                () -> RectangleLowerBoundSolver.solve(0, 1, new Item[0], false));
        assertThrows(IllegalArgumentException.class, () -> new Item("bad", 0.01, 1));
        assertThrows(IllegalArgumentException.class, () -> new Item("bad", Double.NaN, 1));
    }

    @Test
    void readsQuantitiesIdsRotationAndExactDecimalUnits() throws Exception {
        Path csv = temporary.resolve("rectangles.csv");
        Files.writeString(csv, "BatchNo,UPI,Qty,Color,Length,Width,IsSpecial,Rotatable\n"
                + "case,A,3,1,0.3,0.2,0,1\ncase,B,1,1,0.1,0.1,0,0\n");
        Item[] items = Item.getItems(csv.toFile());
        assertEquals(4, items.length);
        assertEquals("A", items[0].id);
        assertEquals(3, items[0].w);
        assertEquals(6L, items[0].s);
        assertFalse(items[0].rotatable);
        assertTrue(items[3].rotatable);
        assertEquals(3, RectangleLowerBoundSolver.solve(5, 2, items, false));
    }

    @Test
    void universalDffsPreserveEverySmallOneDimensionalPacking() {
        for (int capacity = 2; capacity <= 25; capacity++) {
            for (int k = 1; k <= capacity / 2; k++) {
                for (int type : new int[]{0, 2}) {
                    LongUnaryOperator f = DffUtil.function(type, k, capacity, new long[0]);
                    long[] best = new long[capacity + 1];
                    for (int used = 1; used <= capacity; used++) {
                        for (int size = 1; size <= used; size++) {
                            best[used] = Math.max(best[used], best[used - size] + f.applyAsLong(size));
                        }
                    }
                    assertTrue(best[capacity] <= f.applyAsLong(capacity),
                            "DFF failure: C=" + capacity + " k=" + k + " type=" + type);
                }
            }
        }
    }

    @Test
    void dataDependentDffPreservesAllSubsetsIncludingRepeatedSizes() {
        Random random = new Random(20261020L);
        for (int trial = 0; trial < 100; trial++) {
            int capacity = 2 + random.nextInt(24);
            long[] sizes = random.ints(8, 1, capacity + 1).asLongStream().toArray();
            for (int k = 1; k <= capacity / 2; k++) {
                LongUnaryOperator f = DffUtil.function(1, k, capacity, sizes);
                for (int mask = 0; mask < 1 << sizes.length; mask++) {
                    long original = 0;
                    long transformed = 0;
                    for (int i = 0; i < sizes.length; i++) {
                        if ((mask & 1 << i) != 0) {
                            original += sizes[i];
                            transformed += f.applyAsLong(sizes[i]);
                        }
                    }
                    if (original <= capacity) {
                        assertTrue(transformed <= f.applyAsLong(capacity));
                    }
                }
            }
        }
        assertEquals(1, DffUtil.dff2(3, 10, 3, new int[]{1, 1, 3, 3, 6}));
        assertEquals(2, DffUtil.dff2(3, 10, 10, new int[]{1, 1, 3, 3, 6}));
    }

    @Test
    void boundsNeverExceedExactOptimaForSeededSmallInstances() {
        Random random = new Random(819731L);
        for (int trial = 0; trial < 200; trial++) {
            int width = 3 + random.nextInt(3);
            int height = 3 + random.nextInt(3);
            Item[] items = new Item[1 + random.nextInt(6)];
            for (int i = 0; i < items.length; i++) {
                items[i] = item(1 + random.nextInt(width), 1 + random.nextInt(height));
                items[i].rotatable = random.nextBoolean();
            }
            int fixedOptimum = exactOptimum(width, height, items, false);
            int rotatedOptimum = exactOptimum(width, height, items, true);
            assertTrue(BM_LowerBound_Solver.LB_BM_3(width, height, items) <= fixedOptimum);
            assertTrue(CCM_LowerBound_Solver.LB_CCM_1(width, height, items) <= fixedOptimum);
            assertTrue(RectangleLowerBoundSolver.solve(width, height, items, false) <= fixedOptimum);
            assertTrue(RectangleLowerBoundSolver.solve(width, height, items, true) <= rotatedOptimum);
            assertTrue(CCM_LowerBound_Solver.LB_CCM_2(width, height, items, true) <= rotatedOptimum);
        }
    }

    @Test
    void exactGuillotineTilingsRemainOneBinWithOddAndNonSquareDimensions() {
        Random random = new Random(91731L);
        for (int trial = 0; trial < 60; trial++) {
            java.util.List<Item> tiles = new java.util.ArrayList<>();
            tiles.add(item(11, 9));
            while (tiles.size() < 15) {
                int index = random.nextInt(tiles.size());
                Item tile = tiles.get(index);
                boolean splitWidth = tile.w > 1 && (tile.h == 1 || random.nextBoolean());
                if (splitWidth) {
                    int cut = 1 + random.nextInt(tile.w - 1);
                    tiles.set(index, item(cut, tile.h));
                    tiles.add(item(tile.w - cut, tile.h));
                } else if (tile.h > 1) {
                    int cut = 1 + random.nextInt(tile.h - 1);
                    tiles.set(index, item(tile.w, cut));
                    tiles.add(item(tile.w, tile.h - cut));
                }
            }
            Item[] items = tiles.toArray(Item[]::new);
            assertEquals(1, RectangleLowerBoundSolver.solve(11, 9, items, false));
            assertEquals(1, CCM_LowerBound_Solver.LB_CCM_1(11, 9, items));
            for (int i = 0; i < items.length; i++) {
                if (random.nextBoolean()) {
                    items[i] = item(items[i].h, items[i].w);
                }
            }
            assertEquals(1, RectangleLowerBoundSolver.solve(11, 9, items, true));
        }
    }

    /** Exhaustive grid placement + subset partition DP, independent of all lower-bound formulas. */
    private static int exactOptimum(int width, int height, Item[] items, boolean rotate) {
        int states = 1 << items.length;
        boolean[] fits = new boolean[states];
        for (int mask = 1; mask < states; mask++) {
            Item[] subset = new Item[Integer.bitCount(mask)];
            int next = 0;
            long area = 0;
            for (int i = 0; i < items.length; i++) {
                if ((mask & 1 << i) != 0) {
                    subset[next++] = items[i];
                    area += (long) items[i].w * items[i].h;
                }
            }
            Arrays.sort(subset, Comparator.comparingLong((Item i) -> (long) i.w * i.h).reversed());
            fits[mask] = area <= (long) width * height && place(width, height, subset, 0, 0L, rotate);
        }
        int[] best = new int[states];
        Arrays.fill(best, items.length + 1);
        best[0] = 0;
        for (int mask = 1; mask < states; mask++) {
            int first = Integer.lowestOneBit(mask);
            for (int sub = mask; sub > 0; sub = (sub - 1) & mask) {
                if ((sub & first) != 0 && fits[sub]) {
                    best[mask] = Math.min(best[mask], 1 + best[mask ^ sub]);
                }
            }
        }
        return best[states - 1];
    }

    private static boolean place(int width, int height, Item[] items, int index, long occupied,
                                 boolean rotate) {
        if (index == items.length) {
            return true;
        }
        Item item = items[index];
        int orientations = rotate && item.rotatable && item.w != item.h ? 2 : 1;
        for (int orientation = 0; orientation < orientations; orientation++) {
            int w = orientation == 0 ? item.w : item.h;
            int h = orientation == 0 ? item.h : item.w;
            for (int y = 0; y + h <= height; y++) {
                for (int x = 0; x + w <= width; x++) {
                    long cells = 0;
                    for (int row = y; row < y + h; row++) {
                        for (int column = x; column < x + w; column++) {
                            cells |= 1L << (row * width + column);
                        }
                    }
                    if ((occupied & cells) == 0 && place(width, height, items, index + 1,
                            occupied | cells, rotate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
