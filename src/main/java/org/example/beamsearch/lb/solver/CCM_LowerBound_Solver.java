package org.example.beamsearch.lb.solver;

import org.example.beamsearch.lb.entity.Item;
import org.example.beamsearch.lb.util.CommonUtil;
import org.example.beamsearch.lb.util.DffUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/** Fixed-orientation CCM bounds and an orientation-safe extension of the DFF area bound. */
public final class CCM_LowerBound_Solver {
    private CCM_LowerBound_Solver() { }

    public static int LB_CCM_2(int width, int height, Item[] items) {
        return LB_CCM_2(width, height, items, false);
    }

    /**
     * For each DFF pair, minimize EACH physical item's transformed area over its feasible
     * orientations, then sum and round up. Fixed-orientation bounds cannot be reused directly.
     */
    public static int LB_CCM_2(int width, int height, Item[] items, boolean allowRotation) {
        return dffBound(LowerBoundInput.prepare(width, height, items, allowRotation));
    }

    static int dffBound(LowerBoundInput input) {
        if (input.items().length == 0) {
            return 0;
        }
        List<AxisScale> widths = scales(input.width(), input.items(), true);
        List<AxisScale> heights = scales(input.height(), input.items(), false);
        int bound = 0;
        for (AxisScale x : widths) {
            for (AxisScale y : heights) {
                bound = Math.max(bound, transformedAreaBound(x, y));
            }
        }
        return bound;
    }

    private record AxisScale(long capacity, long[] original, long[] rotated) { }

    private static List<AxisScale> scales(int capacity, Item[] items, boolean widthAxis) {
        long[] original = new long[items.length];
        long[] rotated = new long[items.length];
        List<Long> domain = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            Item item = items[i];
            original[i] = widthAxis ? item.w : item.h;
            rotated[i] = item.rotatable ? (widthAxis ? item.h : item.w) : original[i];
            domain.add(original[i]);
            if (item.rotatable) {
                domain.add(rotated[i]);
            }
        }
        long[] sizes = domain.stream().mapToLong(Long::longValue).toArray();
        List<AxisScale> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addScale(result, seen, capacity, original, rotated, x -> x);
        for (long k : CommonUtil.parameters(capacity, CommonUtil.parameterLimit(items.length))) {
            for (int type = 0; type < 3; type++) {
                addScale(result, seen, capacity, original, rotated, DffUtil.function(type, k, capacity, sizes));
            }
        }
        return result;
    }

    private static void addScale(List<AxisScale> result, Set<String> seen, long capacity,
                                 long[] original, long[] rotated, LongUnaryOperator function) {
        long transformedCapacity = function.applyAsLong(capacity);
        if (transformedCapacity == 0) {
            return; // Empty DDFF domain: it contributes no bound and must not be divided by zero.
        }
        long[] a = new long[original.length];
        long[] b = new long[rotated.length];
        long divisor = transformedCapacity;
        for (int i = 0; i < a.length; i++) {
            a[i] = function.applyAsLong(original[i]);
            b[i] = function.applyAsLong(rotated[i]);
            divisor = gcd(divisor, gcd(a[i], b[i]));
        }
        transformedCapacity /= divisor;
        for (int i = 0; i < a.length; i++) {
            a[i] /= divisor;
            b[i] /= divisor;
        }
        String key = transformedCapacity + ":" + Arrays.toString(a) + ":" + Arrays.toString(b);
        if (seen.add(key)) {
            result.add(new AxisScale(transformedCapacity, a, b));
        }
    }

    private static int transformedAreaBound(AxisScale x, AxisScale y) {
        try {
            long denominator = Math.multiplyExact(x.capacity, y.capacity);
            long sum = 0;
            for (int i = 0; i < x.original.length; i++) {
                long original = Math.multiplyExact(x.original[i], y.original[i]);
                long rotated = Math.multiplyExact(x.rotated[i], y.rotated[i]);
                sum = Math.addExact(sum, Math.min(original, rotated));
            }
            return CommonUtil.ceilRatio(sum, denominator);
        } catch (ArithmeticException overflow) {
            // Large dimensions or totals: exact fallback, never floating-point rounding or clipping.
            BigInteger sum = BigInteger.ZERO;
            for (int i = 0; i < x.original.length; i++) {
                BigInteger a = BigInteger.valueOf(x.original[i]).multiply(BigInteger.valueOf(y.original[i]));
                BigInteger b = BigInteger.valueOf(x.rotated[i]).multiply(BigInteger.valueOf(y.rotated[i]));
                sum = sum.add(a.min(b));
            }
            return CommonUtil.ceilRatio(sum,
                    BigInteger.valueOf(x.capacity).multiply(BigInteger.valueOf(y.capacity)));
        }
    }

    /** CCM_1's geometric partitions require fixed orientations. */
    public static int LB_CCM_1(int width, int height, Item[] items) {
        return partitionBound(LowerBoundInput.prepare(width, height, items, false));
    }

    static int partitionBound(LowerBoundInput input) {
        Item[] items = input.items();
        if (items.length == 0) {
            return 0;
        }
        int width = input.width();
        int height = input.height();
        long area = (long) width * height;
        int limit = CommonUtil.parameterLimit(items.length);
        int bound = RectangleLowerBoundSolver.areaBound(input);
        for (long p : CommonUtil.parameters(height, limit)) {
            for (long q : CommonUtil.parameters(width, limit)) {
                int large = 0;
                List<Long> areas = new ArrayList<>();
                List<Long> tallWidths = new ArrayList<>();
                List<Long> wideHeights = new ArrayList<>();
                for (Item item : items) {
                    if (item.w > width - q && item.h > height - p) {
                        large++;
                    } else if (item.w >= q && item.h >= p) {
                        areas.add((long) item.w * item.h);
                        if (item.h > height - p) {
                            tallWidths.add((long) item.w);
                        } else if (item.w > width - q) {
                            wideHeights.add((long) item.h);
                        }
                    }
                }
                int areaPart = oneDimensionalBound(area, areas, limit);
                // Tall and wide classes cannot share a bin; their separate bounds may be added.
                int sidePart = oneDimensionalBound(width, tallWidths, limit)
                        + oneDimensionalBound(height, wideHeights, limit);
                bound = Math.max(bound, large + Math.max(areaPart, sidePart));
            }
        }
        return bound;
    }

    private static int oneDimensionalBound(long capacity, List<Long> values, int limit) {
        if (values.isEmpty()) {
            return 0;
        }
        long[] sizes = values.stream().mapToLong(Long::longValue).toArray();
        int bound = sumBound(capacity, sizes, x -> x);
        for (long k : CommonUtil.parameters(capacity, limit)) {
            for (int type = 0; type < 3; type++) {
                LongUnaryOperator function = DffUtil.function(type, k, capacity, sizes);
                long denominator = function.applyAsLong(capacity);
                if (denominator > 0) {
                    bound = Math.max(bound, sumBound(denominator, sizes, function));
                }
            }
        }
        return bound;
    }

    private static int sumBound(long denominator, long[] values, LongUnaryOperator function) {
        try {
            long sum = 0;
            for (long value : values) {
                sum = Math.addExact(sum, function.applyAsLong(value));
            }
            return CommonUtil.ceilRatio(sum, denominator);
        } catch (ArithmeticException overflow) {
            BigInteger sum = BigInteger.ZERO;
            for (long value : values) {
                sum = sum.add(BigInteger.valueOf(function.applyAsLong(value)));
            }
            return CommonUtil.ceilRatio(sum, BigInteger.valueOf(denominator));
        }
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }
}
