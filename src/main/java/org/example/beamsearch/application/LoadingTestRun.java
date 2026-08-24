package org.example.beamsearch.application;

import org.example.beamsearch.algo.BeamSearch;
import org.example.beamsearch.common.*;
import org.example.beamsearch.lb.entity.Item;
import org.example.beamsearch.lb.solver.BM_LowerBound_Solver;
import org.example.beamsearch.lb.solver.CCM_LowerBound_Solver;
import org.example.beamsearch.spacemanager.SpaceManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LoadingTestRun {
    /**
     *
     * @param materialInPath  Large Board File Path
     * @param workPieceInPath Workpiece File Path
     * @param outPath         Output Path
     * @throws IOException
     */
    public static String[] runWithImprove(String materialInPath, String workPieceInPath, String outPath) throws IOException {
        long startTime = System.currentTimeMillis();

        ProblemLoader problemLoader = new ProblemLoader(workPieceInPath);
        problemLoader.loadContainer(materialInPath);
        ArrayList<Instance> instances = problemLoader.LoadInstancesFromCsv(true, outPath);
        if (instances.size() == 0) {
            return null;
        }

        double maxOptimizeTime = problemLoader.maxOptimizeTime;

        int cntNum = 1000;

        System.out.println("Start " + workPieceInPath + "," + " numOfColor " + instances.size());
        PrintStream oldout = System.out;
        Path oPath = Paths.get(outPath);
        String logPath = oPath.resolve("optimized.log").toString().replace("\\", "/");
        PrintStream out = new PrintStream(new FileOutputStream(logPath), true, "UTF-8");
        System.setOut(out);


        int numOfWorkpiece = 0;
        for (Instance instance : instances) {
            numOfWorkpiece = numOfWorkpiece + instance.totalBoxCount;
        }
        System.out.println("total number of nestable workpieces：" + numOfWorkpiece);

        ExecutionResult exeResult = new ExecutionResult();

        if (instances.size() >= 1) {
            // 为每个优先级组（color）独立排样，最后合并统计
            for (int instIdx = 0; instIdx < instances.size(); instIdx++) {
                Instance instance = instances.get(instIdx);
                String colorTag = (instance.boxes.length > 0) ? instance.boxes[0].color : "?";
                System.out.println("Processing color group: " + colorTag
                        + " (" + (instIdx + 1) + "/" + instances.size() + ")"
                        + ", boxes: " + instance.totalBoxCount);

                // 按工件数量比例分配优化时间
                double allocatedTime = maxOptimizeTime
                        * ((double) instance.totalBoxCount / numOfWorkpiece);
                allocatedTime = Math.max(1, allocatedTime);
                System.out.println("Allocated time: " + String.format("%.1f", allocatedTime) + "s");

                long instStartMs = System.currentTimeMillis();
                ExecutionResult instResult = solve(instance, allocatedTime, cntNum);
                double solveElapsed = (System.currentTimeMillis() - instStartMs) / 1000d;

                // 单实例时计算下界并执行 ImproveSol 优化
                int max = 0;
                if (instances.size() == 1) {
                    try {
                        int index = 1;
                        Item[] items = Item.getItems(new File(workPieceInPath));
                        max = BM_LowerBound_Solver.LB_BM_3(instance.length * index, instance.width * index, items);
                        int r1 = CCM_LowerBound_Solver.LB_CCM_1(instance.length * index, instance.width * index, items);
                        int r2 = CCM_LowerBound_Solver.LB_CCM_2(instance.length * index, instance.width * index, items);
                        max = Math.max(max, r1);
                        max = Math.max(max, r2);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    System.out.println("Lower Bound LB=" + max);
                }

                double improveTime = Math.max(1, allocatedTime - solveElapsed);
                ImproveSol(instance, instResult, cntNum, improveTime, max);

                // 合并到总结果
                exeResult.solutions.addAll(instResult.solutions);
            }
            exeResult.setAvgUtilization();
        }

        int containerCount = exeResult.solutions.size();
        PrintWriter pw;

        String optimizedPath = oPath.resolve("optimized.csv").toString().replace("\\", "/");
        pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(optimizedPath), StandardCharsets.UTF_8)));
        pw.println("BatchNo, BoardNo, Color, UPI, X, Y, R, L, W, Area, Type, SubCode");

        PrintWriter pwStatistics;
        String statisticsPath = oPath.resolve("statistics.csv").toString().replace("\\", "/");
        pwStatistics = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(statisticsPath), StandardCharsets.UTF_8)));
        pwStatistics.println("BatchNo, BoardNo, Color, Specification, BoardArea, WorkPiecesQty, WorkPiecesArea, Ratio, OddArea");

        File dir = new File(outPath, "orderId/");
        if (dir.exists()) {
            File[] files = dir.listFiles();
            for (File file : files) {
                file.delete();
            }
        }
        dir.mkdir();

        int workpieceNum = 0;
        for (int j = 0; j < containerCount; j++) {
            workpieceNum += exeResult.solutions.get(j).getPlacedCuboid().size();
            List<double[]> rectList = new ArrayList<>();
            for (PlacedCuboid p : exeResult.solutions.get(j).getPlacedCuboid()) {
                int orient = 90;
                double rawL = p.box.length;
                double rawW = p.box.width;
                if (p.length == p.box.size[0] && p.width == p.box.size[1]) {
                    orient = 0;
                }

                String id = p.box.ids.get(0).poll();
                if (id == null) {
                    id = p.box.ids.get(1).poll();
                    if (orient == 0) {
                        orient = 90;
                    } else {
                        orient = 0;
                    }
                    rawL = p.box.width;
                    rawW = p.box.length;
                }

                if (p.x < 0 || p.y < 0 || p.x + p.length > exeResult.solutions.get(j).getInst().length
                        || p.y + p.width > exeResult.solutions.get(j).getInst().width) {
                    System.out.println("The nested workpieces in the " + j + "th large board do not meet the trimming requirements of the sheet!!!");
                }

                if (orient == 0) {
                    double[] rect = new double[]{p.x, p.y, p.x + rawL, p.y + rawW};
                    rectList.add(rect);
                } else {
                    double[] rect = new double[]{p.x, p.y, p.x + rawW, p.y + rawL};
                    rectList.add(rect);
                }

                pw.println(p.box.name + "," + (j + 1) + "," + p.box.color + "," + id + ","
                        + p.x + "," + p.y + "," + orient + "," + rawL
                        + "," + rawW + "," + p.box.volume + ",0,0");
                pw.flush();
            }


            FileWriter fw = new FileWriter(new File(dir, "container" + (j + 1) + ".txt"));
            fw.write(exeResult.solutions.get(j).toString());
            fw.close();

            for (int k = 0; k < rectList.size(); k++) {
                double[] rect1 = rectList.get(k);
                for (int l = k + 1; l < rectList.size(); l++) {
                    double[] rect2 = rectList.get(l);
                    if (rect1[0] >= rect2[2]) {
                        continue;
                    } else if (rect1[1] >= rect2[3]) {
                        continue;
                    } else if (rect1[2] <= rect2[0]) {
                        continue;
                    } else if (rect1[3] <= rect2[1]) {
                        continue;
                    }
                    System.out.println("Violation of non-overlapping constraints!!!");
                }
            }

            pwStatistics.println(exeResult.solutions.get(j).getPlacedCuboid().get(0).box.name + "," + (j + 1) + "," + exeResult.solutions.get(j).getPlacedCuboid().get(0).box.color
                    + "," + exeResult.solutions.get(j).getInst().length + "*" + exeResult.solutions.get(j).getInst().width + ","
                    + (exeResult.solutions.get(j).getContainerArea()) + "," + exeResult.solutions.get(j).getPlacedCuboid().size() + "," + (exeResult.solutions.get(j).getBoxesVolume()) + ","
                    + exeResult.solutions.get(j).getUtilization() + "%,");
            pwStatistics.flush();
        }

        String pwtotalPath = oPath.resolve("total.txt").toString().replace("\\", "/");
        PrintWriter pwTotal;
        pwTotal = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(pwtotalPath), StandardCharsets.UTF_8)));
        pwTotal.println(workPieceInPath);
        pwTotal.println("Total number of nestable workpieces in this batch: " + numOfWorkpiece);
        pwTotal.println("Number of sheets used in this batch: " + containerCount);
        pwTotal.println("Average utilization rate of this batch: " + exeResult.avgUtilization + "%");
        pwTotal.println("Running time: " + ((System.currentTimeMillis() - startTime) / 1000d) + "s");

        pw.close();
        pwStatistics.close();
        pwTotal.close();
        System.setOut(oldout);

        System.out.println("Total number of nestable workpieces in this batch: " + numOfWorkpiece);
        System.out.println("Number of sheets used in this batch: " + containerCount);
        System.out.println("Average utilization rate of this batch: " + exeResult.avgUtilization + "%");
        System.out.println("Running time: " + ((System.currentTimeMillis() - startTime) / 1000d) + "s");
        if (numOfWorkpiece == workpieceNum) {
            System.out.println("The algorithm executed successfully and the optimization results have been output.");
        } else {
            System.out.println("The number of workpieces in the result does not match the input data!!!");
        }
        return new String[]{instances.get(0).boxes[0].name, numOfWorkpiece + "", containerCount + "", exeResult.avgUtilization + "%", ((System.currentTimeMillis() - startTime) / 1000d) + "s"};
    }

    private static ExecutionResult solve(Instance instance, double maxTime, int cntNum) {
        int searchTime = 1000;
        int minCon = (int) (instance.totalBoxVolume / (instance.length * instance.width));
        if (minCon > maxTime / 2) {
            searchTime = (int) ((maxTime / (2.0 * minCon)) * 1000);
        }
        System.out.println("searchTime: " + searchTime);
        Comparator<Space> spaceComparator = SpaceComparator.getSpaceComparator(instance, cntNum);
        SpaceManager spaceManager = new SpaceManager(spaceComparator);
        ExecutionResult exeResult = null;
        BeamSearch beamSearch = new BeamSearch(spaceManager, instance);
        exeResult = beamSearch.solve(searchTime, minCon);
        exeResult.setAvgUtilization();
        System.out.println("Found solution: " + exeResult.solutions.size() + "\n\n");
        return exeResult;
    }

    private static void ImproveSol(Instance instance, ExecutionResult exeResult, int cntNum, double maxTime, int LB) {
        if (exeResult.solutions.size() <= LB) {
            System.out.println("Lower bound reached, no optimization required.");
            return;
        }
        if (exeResult.solutions.size() < 4) {
            return;
        }
        Comparator<Space> spaceComparator = SpaceComparator.getSpaceComparator(instance, cntNum);
        SpaceManager spaceManager = new SpaceManager(spaceComparator);
        int containerCnt = exeResult.solutions.size();
        BeamSearch beamSearch = new BeamSearch(spaceManager, instance);
        long startTime = System.currentTimeMillis();
        Random random = new Random(1L);
        exeResult = beamSearch.ImproveByRepack(exeResult, maxTime, random);
        double costTime = (System.currentTimeMillis() - startTime) / 1000d;
        if (exeResult.solutions.size() < containerCnt) {
            if (exeResult.solutions.size() <= LB) {
                System.out.println("Lower bound reached early, optimization terminated.");
                exeResult.setAvgUtilization();

            } else {
                System.out.println("Found solution " + exeResult.solutions.size());
                exeResult.setAvgUtilization();
                maxTime = maxTime - costTime;
                ImproveSol(instance, exeResult, cntNum, maxTime, LB);
            }
        }
    }


    public static void main(String[] args) throws IOException {
        BufferedWriter bw = new BufferedWriter(new FileWriter("F:\\binpack_2D\\AI_result\\total.csv"));
        bw.write("BathName,NumOfWorkpiece,NumOfBoard,Utilization,Time");
        bw.newLine();
        bw.flush();

        String resultPath = "C:\\Users\\DaBiao\\Desktop\\TEST\\0-500";
        String materialPath = "F:\\binpack_2D\\testData\\material.csv";
        File datas = new File("F:\\binpack_2D\\testData\\0-500");

        for (File data : datas.listFiles()) {
            if (data.getName().contains(".txt")) {
                String batchName = data.getName();
                File resultDir = new File(resultPath + "\\" + batchName);
                resultDir.mkdir();
                String workpiecePath = data.getAbsolutePath();
                String[] result = runWithImprove(materialPath, workpiecePath, resultDir.getAbsolutePath() + "\\");
                bw.write(result[0] + "," + result[1] + "," + result[2] + "," + result[3] + "," + result[4]);
                bw.newLine();
                bw.flush();
            }
        }



        bw.close();
    }
}
