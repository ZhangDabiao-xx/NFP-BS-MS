package org.example.beamsearch.lb.util;

import java.math.BigInteger;
import java.util.List;
import java.util.TreeSet;

public final class CommonUtil {
    private CommonUtil() { }

    /** Compatibility helper; solver ratios use exact integer arithmetic. */
    public static int ceilToInt(double x) {
        if (!Double.isFinite(x) || x < 0 || x > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid nonnegative bound: " + x);
        }
        return (int) Math.ceil(x);
    }

    public static int ceilRatio(long numerator, long denominator) {
        if (numerator < 0 || denominator <= 0) {
            throw new IllegalArgumentException("Invalid ratio");
        }
        return Math.toIntExact(numerator / denominator + (numerator % denominator == 0 ? 0 : 1));
    }

    public static int ceilRatio(BigInteger numerator, BigInteger denominator) {
        if (numerator.signum() < 0 || denominator.signum() <= 0) {
            throw new IllegalArgumentException("Invalid ratio");
        }
        BigInteger[] qr = numerator.divideAndRemainder(denominator);
        return qr[0].add(qr[1].signum() == 0 ? BigInteger.ZERO : BigInteger.ONE).intValueExact();
    }

    public static int solve1dCkp(int capacity, int[] sizes) {
        return solve1dCkp(capacity, java.util.Arrays.stream(sizes).asLongStream().sorted().toArray());
    }

    public static int solve1dCkp(int capacity, List<Integer> sizes) {
        return solve1dCkp(capacity, sizes.stream().mapToLong(Integer::longValue).sorted().toArray());
    }

    /** Maximum subset cardinality; sizes must be positive and sorted ascending. */
    public static int solve1dCkp(long capacity, long[] sortedSizes) {
        int count = 0;
        for (long size : sortedSizes) {
            if (size <= 0) {
                throw new IllegalArgumentException("Sizes must be positive");
            }
            if (size > capacity) {
                break;
            }
            capacity -= size;
            count++;
        }
        return count;
    }

    /** Sampling includes both endpoints. A subset of valid candidates is still a valid bound. */
    public static long[] parameters(long capacity, int limit) {
        long half = capacity / 2;
        if (half == 0) {
            return new long[0];
        }
        TreeSet<Long> values = new TreeSet<>();
        long step = Math.max(1, half / Math.max(1, limit));
        for (long k = 1; k <= half; k += step) {
            values.add(k);
        }
        values.add(half);
        return values.stream().mapToLong(Long::longValue).toArray();
    }

    public static int parameterLimit(int itemCount) {
        return Math.max(1, (int) Math.sqrt(1_000_000.0 / Math.max(1, itemCount)));
    }
}
