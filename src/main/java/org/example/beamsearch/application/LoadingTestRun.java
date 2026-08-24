package org.example.beamsearch.application;

import org.example.beamsearch.common.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LoadingTestRun {

    /** 优先件和普通件每张新板的基础搜索时间，单位为毫秒。 */
    private static final int BEAM_SEARCH_TIME_MS = 1_000;

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

        // 先求解优先件，再把优先件板材的剩余空间交给普通件。
        // 当前 BeamSearch 单板搜索沿用原有约 1 秒的搜索窗口；真正的阶段
        // 顺序和状态传递由 PriorityFirstPacker 统一负责。
        System.out.println("Start priority-first combined packing.");
        ExecutionResult exeResult = PriorityFirstPacker.solve(instances, BEAM_SEARCH_TIME_MS);
        exeResult.setAvgUtilization();

        int containerCount = exeResult.solutions.size();
        PrintWriter pw;

        String optimizedPath = oPath.resolve("optimized.csv").toString().replace("\\", "/");
        pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(optimizedPath), StandardCharsets.UTF_8)));
        pw.println("BatchNo, BoardNo, Color, UPI, X, Y, R, L, W, Area, Type, SubCode");

        PrintWriter pwStatistics;
        String statisticsPath = oPath.resolve("statistics.csv").toString().replace("\\", "/");
        pwStatistics = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(statisticsPath), StandardCharsets.UTF_8)));
        pwStatistics.println("BatchNo, BoardNo, Color, Specification, BoardArea, WorkPiecesQty, WorkPiecesArea, Ratio, OddArea, PriorityQty, OrdinaryQty, PriorityArea, OrdinaryArea");

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

                // p.x/p.y/p.length/p.width 都是 BeamSearch 内部的 ×10 整数单位。
                // 重叠检查必须使用同一单位，不能把毫米单位的 rawL/rawW
                // 直接与内部坐标相加。
                rectList.add(new double[]{p.x, p.y, p.x + p.length, p.y + p.width});

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

            writeStatistics(pwStatistics, exeResult.solutions.get(j), j + 1);
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
        return new String[]{firstPlacedName(exeResult), numOfWorkpiece + "", containerCount + "", exeResult.avgUtilization + "%", ((System.currentTimeMillis() - startTime) / 1000d) + "s"};
    }

    /**
     * 输出一张板材的统计信息。整体排样后同一张板可以同时包含两种 Color，
     * 因此不能再使用第一件工件的颜色代表整张板。
     */
    private static void writeStatistics(PrintWriter writer, Solution solution, int boardNumber) {
        int priorityCount = 0;
        int ordinaryCount = 0;
        double priorityArea = 0;
        double ordinaryArea = 0;

        for (PlacedCuboid placedCuboid : solution.getPlacedCuboid()) {
            if ("1".equals(placedCuboid.box.color)
                    || "true".equalsIgnoreCase(placedCuboid.box.color)) {
                priorityCount++;
                priorityArea += placedCuboid.getVolume();
            } else {
                ordinaryCount++;
                ordinaryArea += placedCuboid.getVolume();
            }
        }

        String color = priorityCount > 0 && ordinaryCount > 0
                ? "MIXED"
                : priorityCount > 0 ? "1" : "0";

        writer.println(solution.getPlacedCuboid().isEmpty()
                ? "unknown," + boardNumber + "," + color + ","
                + solution.getInst().length + "*" + solution.getInst().width + ","
                + solution.getContainerArea() + ",0,0,0%,0,0,0,0,0"
                : solution.getPlacedCuboid().get(0).box.name + "," + boardNumber + "," + color + ","
                + solution.getInst().length + "*" + solution.getInst().width + ","
                + solution.getContainerArea() + "," + solution.getPlacedCuboid().size() + ","
                + solution.getBoxesVolume() + "," + solution.getUtilization() + "%,"
                + priorityCount + "," + ordinaryCount + "," + priorityArea + "," + ordinaryArea);
    }

    private static String firstPlacedName(ExecutionResult result) {
        for (Solution solution : result.solutions) {
            if (!solution.getPlacedCuboid().isEmpty()) {
                return solution.getPlacedCuboid().get(0).box.name;
            }
        }
        return "unknown";
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
