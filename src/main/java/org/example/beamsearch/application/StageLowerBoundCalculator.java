package org.example.beamsearch.application;

import org.example.beamsearch.common.Box;
import org.example.beamsearch.common.Instance;
import org.example.beamsearch.common.StageLowerBound;
import org.example.beamsearch.lb.entity.Item;
import org.example.beamsearch.lb.solver.RectangleLowerBoundSolver;

import java.util.ArrayList;

/** 将混排阶段已经矩形化的物品转换为下界输入，不修改原 Box 或其物品编号队列。 */
final class StageLowerBoundCalculator {
    static final String PRIORITY = "priority_rectangles";
    static final String REMAINING_ORDINARY = "remaining_ordinary_rectangles";
    static final String ORDINARY = "ordinary_rectangles";

    private StageLowerBoundCalculator() { }

    static StageLowerBound calculate(Instance instance, String scope) {
        long start = System.nanoTime();
        ArrayList<Item> items = new ArrayList<>();
        int rotatableCount = 0;
        for (Box box : instance.boxes) {
            if (box.count < 0) {
                throw new IllegalArgumentException("Negative item quantity in stage lower bound: " + box.id);
            }
            if (box.count == 0) {
                continue;
            }
            int length = 0;
            int width = 0;
            boolean rotatable = false;
            if (box.variation == null) {
                throw new IllegalArgumentException("Missing allowed orientations: " + box.id);
            }
            // variation 使用 [宽, 长]，且已经包含物品自身及板材方向的限制。
            // 只保留能放入当前板材的方向，不能依据混合容器的统一旋转标志推断物品能力。
            for (int[] orientation : box.variation) {
                if (orientation == null || orientation.length != 2) {
                    throw new IllegalArgumentException("Invalid allowed orientation: " + box.id);
                }
                int candidateLength = orientation[1];
                int candidateWidth = orientation[0];
                if (candidateLength <= 0 || candidateWidth <= 0
                        || candidateLength > instance.length || candidateWidth > instance.width) {
                    continue;
                }
                if (length == 0) {
                    length = candidateLength;
                    width = candidateWidth;
                } else if (candidateLength == width && candidateWidth == length) {
                    rotatable |= length != width;
                } else if (candidateLength != length || candidateWidth != width) {
                    throw new IllegalArgumentException("Allowed orientations must describe one rectangle: " + box.id);
                }
            }
            if (length == 0) {
                throw new IllegalArgumentException("No feasible orientation in stage lower bound: " + box.id);
            }
            // Box 和 Item.fromScaledUnits 共用内部整数单位；不能再次乘十。
            // 每个数量副本是一件物理矩形；读取 id 不会消耗输出 CSV 所需的 ids 队列。
            for (int copy = 0; copy < box.count; copy++) {
                items.add(Item.fromScaledUnits(box.id + "#" + copy, length, width, rotatable));
            }
            if (rotatable) {
                rotatableCount += box.count;
            }
        }
        // true 只启用“按物品判断”的旋转支持，各 Item 的 rotatable 仍独立生效。
        RectangleLowerBoundSolver.Result calculation = RectangleLowerBoundSolver.solveDetailed(
                instance.length, instance.width, items.toArray(Item[]::new), true, false);
        StageLowerBound bound = new StageLowerBound(scope, items.size(), rotatableCount,
                calculation, Math.max(0L, System.nanoTime() - start));
        System.out.println("[Stage lower bound] scope=" + scope + ", lowerBound=" + bound.lowerBound()
                + ", items=" + bound.itemCount() + ", rotatable=" + bound.rotatableItemCount()
                + ", fixed=" + bound.fixedItemCount() + ", rotationPolicy=per_item"
                + ", calculationTimeMs=" + bound.elapsedNanos() / 1_000_000.0);
        return bound;
    }
}
