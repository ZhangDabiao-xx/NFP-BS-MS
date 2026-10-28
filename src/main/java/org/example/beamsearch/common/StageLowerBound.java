package org.example.beamsearch.common;

import org.example.beamsearch.lb.solver.RectangleLowerBoundSolver;

/**
 * 混排中一个独立优化阶段的下界记录。
 * 只对应本阶段已经确定的矩形/组块集合，不代表原始多边形案例的全局下界。
 */
public record StageLowerBound(String scope, int itemCount, int rotatableItemCount,
                              RectangleLowerBoundSolver.Result calculation, long elapsedNanos) {
    public int lowerBound() {
        return calculation.lowerBound();
    }

    public int fixedItemCount() {
        return itemCount - rotatableItemCount;
    }
}
