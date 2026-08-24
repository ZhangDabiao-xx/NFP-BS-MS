package org.example.beamsearch.common;
import java.util.*;

public class ExecutionResult {
    public ArrayList<Box> unplacedBoxes;
    public double unplacedBoxesVol;
    public Solution solution;
    public ArrayList<Solution> solutions = new ArrayList<Solution>();
    public double avgUtilization;


    public void setAvgUtilization() {
        double totalWorkArea = 0;
        double totalBoardArea = 0;
        for (Solution s : solutions) {
            totalWorkArea += s.getBoxesVolume();
            totalBoardArea += s.getContainerArea();
        }
        this.avgUtilization = 100 * (totalWorkArea / totalBoardArea);
    }
}

