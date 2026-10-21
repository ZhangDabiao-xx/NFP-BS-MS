package org.example.beamsearch.lb.util;

import java.util.Arrays;
import java.util.List;
import java.util.function.LongUnaryOperator;

/** Discrete DFFs of Carlier, Clautiaux and Moukrim (2007), types 0, 1 and 2. */
public final class DffUtil {
    private DffUtil() { }

    public static int dff(int type, int k, int capacity, int x, List<Integer> sizes) {
        return dff(type, k, capacity, x, sizes.stream().mapToInt(Integer::intValue).toArray());
    }

    public static int dff(int type, int k, int capacity, int x, int[] sizes) {
        return Math.toIntExact(function(type, k, capacity,
                Arrays.stream(sizes).asLongStream().toArray()).applyAsLong(x));
    }

    public static int dff1(int k, int capacity, int x) {
        return Math.toIntExact(function(0, k, capacity, new long[0]).applyAsLong(x));
    }

    public static int dff2(int k, int capacity, int x, List<Integer> sizes) {
        return dff(1, k, capacity, x, sizes);
    }

    public static int dff2(int k, int capacity, int x, int[] sizes) {
        return dff(1, k, capacity, x, sizes);
    }

    public static int dff3(int k, int capacity, int x) {
        return Math.toIntExact(function(2, k, capacity, new long[0]).applyAsLong(x));
    }

    /**
     * Build once per parameter. DDFF sizes must contain every possible dimension occurrence,
     * with multiplicity. For rotation the caller supplies BOTH allowed orientations; extra
     * alternatives enlarge the domain of subsets and therefore remain safe.
     */
    public static LongUnaryOperator function(int type, long k, long capacity, long[] sizes) {
        if (capacity <= 0 || capacity > Long.MAX_VALUE / 2 || k < 1 || k > capacity / 2) {
            throw new IllegalArgumentException("Invalid DFF capacity or parameter");
        }
        LongUnaryOperator transform;
        switch (type) {
            case 0 -> transform = x -> x > capacity - k ? capacity : x >= k ? x : 0;
            case 1 -> {
                long[] selected = Arrays.stream(sizes).filter(x -> x >= k && x <= capacity / 2)
                        .sorted().toArray();
                int count = CommonUtil.solve1dCkp(capacity, selected);
                long[] prefix = new long[count + 1];
                for (int i = 0; i < count; i++) {
                    prefix[i + 1] = prefix[i] + selected[i];
                }
                transform = x -> x > capacity / 2
                        ? count - cardinality(capacity - x, prefix) : x >= k ? 1 : 0;
            }
            case 2 -> transform = x -> {
                if (x > capacity / 2) {
                    return 2 * (capacity / k - (capacity - x) / k);
                }
                // Equality to floor(C/2) is not equality to C/2 for an odd C.
                if (capacity % 2 == 0 && x == capacity / 2) {
                    return capacity / k;
                }
                return 2 * (x / k);
            };
            default -> throw new IllegalArgumentException("Unknown DFF type: " + type);
        }
        return x -> {
            if (x < 0 || x > capacity) {
                throw new IllegalArgumentException("DFF argument outside [0, capacity]");
            }
            return transform.applyAsLong(x);
        };
    }

    private static int cardinality(long capacity, long[] prefix) {
        int low = 0;
        int high = prefix.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (prefix[middle] <= capacity) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }
}
