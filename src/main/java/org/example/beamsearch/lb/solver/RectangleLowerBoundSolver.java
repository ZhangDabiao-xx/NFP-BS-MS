package org.example.beamsearch.lb.solver;

import org.example.beamsearch.lb.entity.Item;
import org.example.beamsearch.lb.util.CommonUtil;

import java.math.BigInteger;

/** Standalone rectangle lower bounds. No dependency on the packing or Agent workflow. */
public final class RectangleLowerBoundSolver {
    private RectangleLowerBoundSolver() { }

    public record Result(int lowerBound, int areaBound, int dffBound, Integer bmBound,
                         Integer ccm1Bound, boolean allowRotation, long elapsedNanos) { }

    /** Board dimensions are integers in the SAME units as Item.w/h. */
    public static int solve(int width, int height, Item[] items, boolean allowRotation) {
        return solveDetailed(width, height, items, allowRotation, false).lowerBound();
    }

    /** CCM_1 is optional because its additional parameter loops can be expensive. */
    public static Result solveDetailed(int width, int height, Item[] items, boolean allowRotation,
                                       boolean includeCcm1) {
        long start = System.nanoTime();
        LowerBoundInput input = LowerBoundInput.prepare(width, height, items, allowRotation);
        int area = areaBound(input);
        int dff = CCM_LowerBound_Solver.dffBound(input);
        Integer bm = null;
        Integer ccm1 = null;
        int bound = Math.max(area, dff);
        // Never evaluate fixed-orientation partitions when an item still has two orientations.
        if (!input.hasRotation()) {
            bm = BM_LowerBound_Solver.fixedBound(input);
            bound = Math.max(bound, bm);
            if (includeCcm1) {
                ccm1 = CCM_LowerBound_Solver.partitionBound(input);
                bound = Math.max(bound, ccm1);
            }
        }
        return new Result(bound, area, dff, bm, ccm1, allowRotation, System.nanoTime() - start);
    }

    static int areaBound(LowerBoundInput input) {
        BigInteger sum = BigInteger.ZERO;
        for (Item item : input.items()) {
            sum = sum.add(BigInteger.valueOf((long) item.w * item.h));
        }
        return CommonUtil.ceilRatio(sum, BigInteger.valueOf((long) input.width() * input.height()));
    }
}
