package org.example.beamsearch.common;
import java.util.*;

public class ExecutionResult {
    public ArrayList<Box> unplacedBoxes;
    /** 每种 Box 当前剩余的数量，数组下标与对应 Instance.boxes 一致。 */
    public int[] unplacedCounts;
    public double unplacedBoxesVol;
    public Solution solution;
    public ArrayList<Solution> solutions = new ArrayList<Solution>();
    /**
     * 每张已完成板材的几何状态快照，供后续阶段继续排样使用。
     */
    public ArrayList<BoardStateSnapshot> boardStates = new ArrayList<>();
    /** 最终结果中含有优先件的板材数，即 Sp。 */
    public int priorityBoardCount;
    /** 最终结果中只用于普通件新板的板材数，即 So。 */
    public int ordinaryBoardCount;

    /** 优先件新板求解阶段实际耗时，单位为毫秒。 */
    public long prioritySolveTimeMs;
    /** 优先件全局优化阶段实际耗时，单位为毫秒。 */
    public long priorityOptimizeTimeMs;
    /** 向优先件板材插入普通件阶段实际耗时，单位为毫秒。 */
    public long ordinaryInsertionTimeMs;
    /** 普通件新板求解阶段实际耗时，单位为毫秒。 */
    public long ordinarySolveTimeMs;
    /** 普通件新板全局优化阶段实际耗时，单位为毫秒。 */
    public long ordinaryOptimizeTimeMs;
    /** 上述排样和优化阶段的总实际耗时，单位为毫秒。 */
    public long totalSolveTimeMs;
    /** 原有基于近似矩形面积计算的平均利用率，单位为百分比。 */
    public double avgUtilization;
    /** 每张最终使用容器的真实工件面积利用率 U，按 solutions 顺序保存，取值为 [0, 1]。 */
    public ArrayList<Double> actualContainerUtilizations = new ArrayList<>();
    /** 最终排样中全部真实多边形工件的面积和。 */
    public double actualTotalWorkpieceArea;
    /** 等效容器数量 N = Nb - 1 + min(U)，其中 Nb 为实际使用容器数。 */
    public double equivalentContainerCount;
    /** 基于真实工件面积和等效容器数量的平均利用率 Uagv，取值为 [0, 1]。 */
    public double actualAverageUtilization;


    public void setAvgUtilization() {
        double totalWorkArea = 0;
        double totalBoardArea = 0;
        for (Solution s : solutions) {
            totalWorkArea += s.getBoxesVolume();
            totalBoardArea += s.getContainerArea();
        }
        this.avgUtilization = totalBoardArea == 0
                ? 0
                : 100 * (totalWorkArea / totalBoardArea);
    }

    /**
     * 计算不参与求解的真实面积统计指标 U、N 与 Uagv。
     *
     * <p>U 使用 NFP block 内所有原始多边形面积之和，不使用矩形外接框面积；
     * N 按 {@code Nb - 1 + min(U)} 计算；Uagv 按 {@code sum(s)/(N*W*H)}
     * 计算。当前优先/普通件混合流程已验证所有容器尺寸相同，因此 W*H 取
     * 最终容器的共同面积。该方法只更新统计字段，不改变任何排样决策。</p>
     */
    public void setActualUtilizationMetrics() {
        actualContainerUtilizations.clear();
        actualTotalWorkpieceArea = 0.0;
        double boardArea = 0.0;
        double minimumUtilization = Double.POSITIVE_INFINITY;

        for (Solution solution : solutions) {
            double actualArea = 0.0;
            for (PlacedCuboid placedCuboid : solution.getPlacedCuboid()) {
                actualArea += Math.max(0.0, placedCuboid.getActualWorkpieceArea());
            }
            actualTotalWorkpieceArea += actualArea;
            // BeamSearch 内部坐标按长度放大 10 倍，面积需除以 100 后才与
            // NFP 顶点计算出的平方毫米真实面积处于同一单位。
            double containerArea = Math.max(0.0, solution.getContainerArea() / 100.0);
            if (boardArea <= 0.0 && containerArea > 0.0) {
                boardArea = containerArea;
            }
            double utilization = containerArea <= 0.0 ? 0.0 : actualArea / containerArea;
            actualContainerUtilizations.add(utilization);
            minimumUtilization = Math.min(minimumUtilization, utilization);
        }

        int containerCount = solutions.size();
        equivalentContainerCount = containerCount == 0
                ? 0.0
                : containerCount - 1.0 + minimumUtilization;
        actualAverageUtilization = equivalentContainerCount <= 0.0 || boardArea <= 0.0
                ? 0.0
                : actualTotalWorkpieceArea / (equivalentContainerCount * boardArea);
    }
}

