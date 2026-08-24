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

