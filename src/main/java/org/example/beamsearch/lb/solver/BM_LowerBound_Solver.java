package org.example.beamsearch.lb.solver;

import org.example.beamsearch.lb.entity.Item;
import org.example.beamsearch.lb.util.CommonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** BM_3 for fixed orientations. Use RectangleLowerBoundSolver for a rotation-aware bound. */
public final class BM_LowerBound_Solver {
    private BM_LowerBound_Solver() { }

    private static int cardinality(int capacity, List<Integer> sizes) {
        int count = 0;
        for (int size : sizes) {
            if (size > capacity) {
                break;
            }
            capacity -= size;
            count++;
        }
        return count;
    }

    private static long residualCapacity(Item item, List<Integer> widths, List<Integer> heights,
                                         int width, int height) {
        long a = cardinality(width - item.w, widths);
        long b = cardinality(height - item.h, heights);
        return a * cardinality(height, heights) + cardinality(width, widths) * b - a * b;
    }

    public static int LB_BM_3(int width, int height, Item[] items) {
        return fixedBound(LowerBoundInput.prepare(width, height, items, false));
    }

    static int fixedBound(LowerBoundInput input) {
        Item[] items = input.items();
        if (items.length == 0) {
            return 0;
        }
        int width = input.width();
        int height = input.height();
        int bound = RectangleLowerBoundSolver.areaBound(input);
        int limit = CommonUtil.parameterLimit(items.length);
        // p is a HEIGHT threshold; q is a WIDTH threshold.
        for (long p : CommonUtil.parameters(height, limit)) {
            for (long q : CommonUtil.parameters(width, limit)) {
                List<Integer> widths = new ArrayList<>();
                List<Integer> heights = new ArrayList<>();
                for (Item item : items) {
                    if (!(item.w > width / 2 && item.h > height / 2) && item.w >= q && item.h >= p) {
                        widths.add(item.w);
                        heights.add(item.h);
                    }
                }
                Collections.sort(widths);
                Collections.sort(heights);
                int large = 0;
                long remaining = 0;
                for (Item item : items) {
                    if (item.w > width - q && item.h > height - p) {
                        large++;
                    } else if (item.w > width / 2 && item.h > height / 2) {
                        large++;
                        remaining = Math.subtractExact(remaining,
                                residualCapacity(item, widths, heights, width, height));
                    } else if (item.w >= q && item.h >= p) {
                        remaining++;
                    }
                }
                long capacity = (long) cardinality(width, widths) * cardinality(height, heights);
                int extra = remaining > 0 && capacity > 0 ? CommonUtil.ceilRatio(remaining, capacity) : 0;
                bound = Math.max(bound, large + extra);
            }
        }
        return bound;
    }
}
