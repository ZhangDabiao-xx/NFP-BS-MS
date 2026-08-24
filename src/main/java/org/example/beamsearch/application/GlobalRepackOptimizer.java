package org.example.beamsearch.application;

import org.example.beamsearch.algo.BeamSearch;
import org.example.beamsearch.common.BoardStateSnapshot;
import org.example.beamsearch.common.Box;
import org.example.beamsearch.common.ExecutionResult;
import org.example.beamsearch.common.Instance;
import org.example.beamsearch.common.PlacedCuboid;
import org.example.beamsearch.common.Solution;
import org.example.beamsearch.common.Space;
import org.example.beamsearch.common.SpaceComparator;
import org.example.beamsearch.spacemanager.SpaceManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Random;

/**
 * 对一个独立排样阶段的结果执行全局重排优化。
 *
 * <p>BeamSearch.solve() 主要负责逐板生成可行解，真正的跨板材合并由
 * BeamSearch.ImproveByRepack() 完成。本类把这两步之间的状态维护集中起来：</p>
 *
 * <ul>
 *     <li>调用已有的跨板材重排逻辑；</li>
 *     <li>将重排产生的 Box 副本重新绑定到当前 Instance 的 Box；</li>
 *     <li>重新计算未排数量；</li>
 *     <li>根据新的布局重新生成剩余空间快照。</li>
 * </ul>
 *
 * <p>调用方应当对一个独立阶段使用本类。优先件阶段和普通件新板阶段
 * 可以分别优化，但不能把已经包含优先件的混合板材交给这里重排。</p>
 */
public final class GlobalRepackOptimizer {

    /** 搜索时间较短时，仍为全局重排保留一个最小的可用时间窗口。 */
    private static final int MIN_REPACK_TIME_MS = 5_000;

    private GlobalRepackOptimizer() {
    }

    /**
     * 优化一个独立排样阶段的板材数量。
     *
     * @param instance 当前阶段使用的 Instance
     * @param result 当前阶段的逐板排样结果
     * @param timeLimitMs 全局重排时间，单位为毫秒
     * @return 优化并重新建立状态快照后的结果
     */
    public static ExecutionResult optimize(Instance instance,
                                            ExecutionResult result,
                                            int timeLimitMs) {
        if (instance == null || result == null) {
            return result;
        }

        // solve() 生成的 Box 通常已经是原对象，但重排过程中会使用 Box.copy()。
        // 先统一一次，保证重排和后续快照都使用同一组工件定义。
        normalizeResult(instance, result);

        int boardCountBefore = result.solutions.size();
        if (boardCountBefore >= 2 && timeLimitMs > 0) {
            Comparator<Space> comparator = SpaceComparator.getSpaceComparator(instance, 1);
            SpaceManager spaceManager = new SpaceManager(comparator);
            BeamSearch beamSearch = new BeamSearch(spaceManager, instance);

            // ImproveByRepack() 接收秒数。最小时间窗口是为了避免 LoadingTestRun
            // 的短单板搜索时间导致全局优化几乎没有机会执行。
            int repackTimeMs = Math.max(MIN_REPACK_TIME_MS, timeLimitMs);
            double repackTimeSeconds = repackTimeMs / 1000.0;
            System.out.println("Start global repack optimization for "
                    + boardCountBefore + " boards, time=" + repackTimeSeconds + "s.");

            // 固定随机种子，保证同一输入下的重排顺序可复现。
            ExecutionResult optimized = beamSearch.ImproveByRepack(
                    result,
                    repackTimeSeconds,
                    new Random(1L));
            if (optimized != null) {
                result = optimized;
            }

            System.out.println("Global repack result: "
                    + boardCountBefore + " -> " + result.solutions.size() + " boards.");
        }

        // ImproveByRepack() 可能替换 Solution 和 PlacedCuboid，旧 boardStates
        // 已经不再对应当前布局，必须在阶段边界重新建立。
        normalizeResult(instance, result);
        rebuildBoardStates(instance, result);
        result.setAvgUtilization();
        return result;
    }

    /**
     * 将重排过程中创建的 Box 副本绑定回当前阶段的原始 Box，并重算未排数量。
     */
    private static void normalizeResult(Instance instance, ExecutionResult result) {
        int[] placedCounts = new int[instance.boxes.length];

        for (Solution solution : result.solutions) {
            for (PlacedCuboid placedCuboid : solution.getPlacedCuboid()) {
                if (placedCuboid.box == null) {
                    continue;
                }
                Box canonicalBox = findCanonicalBox(instance, placedCuboid.box);
                placedCuboid.box = canonicalBox;

                int boxIndex = findBoxIndex(instance, canonicalBox);
                if (boxIndex >= 0) {
                    placedCounts[boxIndex]++;
                }
            }
        }

        result.unplacedCounts = new int[instance.boxes.length];
        result.unplacedBoxes = new ArrayList<>();
        result.unplacedBoxesVol = 0;

        for (int i = 0; i < instance.boxes.length; i++) {
            int remaining = instance.boxes[i].count - placedCounts[i];
            result.unplacedCounts[i] = Math.max(0, remaining);
            if (remaining > 0) {
                result.unplacedBoxes.add(instance.boxes[i]);
                result.unplacedBoxesVol += instance.boxes[i].volume * remaining;
            }
        }

        if (!result.solutions.isEmpty()) {
            result.solution = result.solutions.get(result.solutions.size() - 1);
        }
    }

    /**
     * 根据优化后的 Solution 重新计算每张板材的几何剩余空间。
     */
    private static void rebuildBoardStates(Instance instance,
                                           ExecutionResult result) {
        result.boardStates.clear();
        for (Solution solution : result.solutions) {
            ArrayList<Space> remainingSpaces = SpaceManager.calculateResidualSpaces(
                    instance.length,
                    instance.width,
                    solution.getPlacedCuboid());
            result.boardStates.add(new BoardStateSnapshot(
                    solution.getPlacedCuboid(),
                    remainingSpaces));
        }
    }

    /** 优先使用对象引用匹配，重排后的副本再使用稳定字段匹配。 */
    private static Box findCanonicalBox(Instance instance, Box sourceBox) {
        if (sourceBox == null) {
            return null;
        }

        for (Box box : instance.boxes) {
            if (box == sourceBox) {
                return box;
            }
        }

        for (Box box : instance.boxes) {
            if (sameBoxDefinition(box, sourceBox)) {
                return box;
            }
        }
        return sourceBox;
    }

    private static boolean sameBoxDefinition(Box left, Box right) {
        if (!sameText(left.color, right.color)) {
            return false;
        }

        boolean sameShape = Double.compare(left.length, right.length) == 0
                && Double.compare(left.width, right.width) == 0;
        if (!sameShape) {
            return false;
        }

        if (hasText(left.id) && hasText(right.id)) {
            return left.id.equals(right.id);
        }
        if (hasText(left.name) && hasText(right.name)) {
            return left.name.equals(right.name);
        }
        return left.typeNum == right.typeNum;
    }

    private static int findBoxIndex(Instance instance, Box targetBox) {
        for (int i = 0; i < instance.boxes.length; i++) {
            if (instance.boxes[i] == targetBox) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static boolean sameText(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
