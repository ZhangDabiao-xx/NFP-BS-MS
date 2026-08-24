package org.example.beamsearch.common;

import org.example.beamsearch.state.State;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一张板材在某个求解阶段结束时的状态快照。
 *
 * <p>当前 BeamSearch 的 {@link State} 是搜索过程中的内部对象，原有
 * {@link ExecutionResult} 只保存了最终放置结果，没有保存剩余空间。
 * 这个类把后续阶段真正需要的信息单独保存下来：</p>
 *
 * <ul>
 *     <li>已经放置的矩形及其坐标；</li>
 *     <li>根据这些矩形重新计算得到的几何剩余空间。</li>
 * </ul>
 *
 * <p>所有坐标都使用 BeamSearch 内部的整数坐标，即毫米坐标乘以 10。</p>
 */
public final class BoardStateSnapshot {

    private final List<PlacedCuboid> placedCuboids;
    private final List<Space> remainingSpaces;

    public BoardStateSnapshot(List<PlacedCuboid> placedCuboids,
                              List<Space> remainingSpaces) {
        this.placedCuboids = copyPlacedCuboids(placedCuboids);
        this.remainingSpaces = copySpaces(remainingSpaces);
    }

    /**
     * 返回已放置矩形的副本，避免后续阶段修改快照内部数据。
     */
    public List<PlacedCuboid> getPlacedCuboids() {
        return copyPlacedCuboids(placedCuboids);
    }

    /**
     * 返回几何剩余空间的副本。
     */
    public List<Space> getRemainingSpaces() {
        return copySpaces(remainingSpaces);
    }

    /**
     * 将快照中的矩形转换为指定 Instance 下的 Solution。
     *
     * <p>优先件阶段和混合排样阶段的 Instance 可能不同，但板材尺寸必须
     * 相同，因此这里不复用快照原来的 Instance，而由调用方明确传入目标
     * Instance。</p>
     */
    public Solution toSolution(Instance instance) {
        Solution solution = new Solution(instance);
        for (PlacedCuboid placedCuboid : placedCuboids) {
            solution.add(placedCuboid.clone());
        }
        return solution;
    }

    private static List<PlacedCuboid> copyPlacedCuboids(List<PlacedCuboid> source) {
        List<PlacedCuboid> result = new ArrayList<>();
        if (source != null) {
            for (PlacedCuboid placedCuboid : source) {
                if (placedCuboid != null) {
                    result.add(placedCuboid.clone());
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Space> copySpaces(List<Space> source) {
        List<Space> result = new ArrayList<>();
        if (source != null) {
            for (Space space : source) {
                if (space != null) {
                    result.add(new Space(space.x1, space.y1, space.x2, space.y2));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }
}
