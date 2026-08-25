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
    public double avgUtilization;


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
}

