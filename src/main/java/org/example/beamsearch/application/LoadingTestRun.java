package org.example.beamsearch.application;

import org.example.beamsearch.common.*;
import org.example.qlearning.QLearningSession;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LoadingTestRun {

    /** 全局重排优化的总时间预算，不包含初始排样、普通件插入、NFP 拼接和组块生成，单位为毫秒。 */
    private static final long TOTAL_SOLVE_TIME_MS = PackingRuntimeConfig.totalSolveTimeMs();

    /**
     * 读取桥接层生成的板材与工件文件，执行优先级排样并写出排样结果文件。
     *
     * @param materialInPath 板材 CSV 文件路径，包含板材长度、宽度和颜色组
     * @param workPieceInPath 工件文件路径，包含矩形化后的 NFP 组块
     * @param outPath 单案例排样结果目录，用于写入 CSV、统计和运行日志
     * @return 案例名称、工件数、实际/等效容器数、真实平均利用率、Sp、So 和耗时组成的摘要；没有可排样工件时返回 {@code null}
     * @throws IOException 当输入文件无法读取或结果文件无法写入时抛出
     */
    public static String[] runWithImprove(String materialInPath, String workPieceInPath, String outPath) throws IOException {
        return runWithImprove(materialInPath, workPieceInPath, outPath, QLearningSession.disabled());
    }

    /**
     * 读取桥接层输入、执行优先级排样，并可选地将 Q-learning 会话传递给排样器。
     *
     * @param materialInPath 板材 CSV 文件路径，包含板材长度、宽度和颜色组。
     * @param workPieceInPath 工件文件路径，包含矩形化后的 NFP 组块。
     * @param outPath 单案例排样结果目录，用于写入 CSV、统计和运行日志。
     * @param qLearningSession 当前一体化运行共享的 Q-learning 会话；关闭时保持原有启发式行为。
     * @return 案例名称、工件数、实际/等效容器数、真实平均利用率、Sp、So 和耗时组成的摘要；没有可排样工件时返回 {@code null}。
     * @throws IOException 当输入文件无法读取或结果文件无法写入时抛出。
     */
    public static String[] runWithImprove(String materialInPath,
                                          String workPieceInPath,
                                          String outPath,
                                          QLearningSession qLearningSession) throws IOException {
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
        // 600 秒由 PriorityFirstPacker 在优先件和普通件全局重排阶段之间统一分配；
        // 初始排样和普通件插入 Sp 独立计时，不扣减该预算。
        System.out.println("Start priority-first combined packing.");
        ExecutionResult exeResult = PriorityFirstPacker.solveWithTotalTime(
                instances,
                TOTAL_SOLVE_TIME_MS,
                qLearningSession);
        // 保留原近似矩形利用率，同时额外计算真实多边形面积口径的 U、N、Uagv。
        exeResult.setAvgUtilization();
        exeResult.setActualUtilizationMetrics();

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
            fw.write("Actual workpiece utilization U: "
                    + formatPercent(exeResult.actualContainerUtilizations.get(j)) + "%\n");
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
        pwTotal.println("Equivalent container count (N): " + formatDecimal(exeResult.equivalentContainerCount));
        pwTotal.println("Priority containers (Sp): " + exeResult.priorityBoardCount);
        pwTotal.println("Ordinary containers (So): " + exeResult.ordinaryBoardCount);
        pwTotal.println("S = Sp + So: " + (exeResult.priorityBoardCount + exeResult.ordinaryBoardCount));
        pwTotal.println("Average utilization rate of this batch: " + exeResult.avgUtilization + "%");
        pwTotal.println("Actual average utilization (Uagv): "
                + formatPercent(exeResult.actualAverageUtilization) + "%");
        writeSolveTiming(pwTotal, exeResult);
        pwTotal.println("Running time: " + ((System.currentTimeMillis() - startTime) / 1000d) + "s");

        pw.close();
        pwStatistics.close();
        pwTotal.close();
        System.setOut(oldout);

        System.out.printf(Locale.ROOT,
                "结果: 工件数量 %d, 容器使用数量 %d, 容器数量 N %s, 平均利用率 Uagv %s%%, Sp %d, So %d, 初始排样耗时 %ss, 全局优化耗时 %ss, 插入耗时 %ss, 总排样耗时 %ss%n",
                numOfWorkpiece,
                containerCount,
                formatDecimal(exeResult.equivalentContainerCount),
                formatPercent(exeResult.actualAverageUtilization),
                exeResult.priorityBoardCount,
                exeResult.ordinaryBoardCount,
                formatSeconds(exeResult.initialPackingTimeMs),
                formatSeconds(exeResult.totalSolveTimeMs),
                formatSeconds(exeResult.ordinaryInsertionTimeMs),
                formatSeconds(exeResult.totalPackingTimeMs));
        if (numOfWorkpiece == workpieceNum) {
            System.out.println("The algorithm executed successfully and the optimization results have been output.");
        } else {
            System.out.println("The number of workpieces in the result does not match the input data!!!");
        }
        return new String[]{firstPlacedName(exeResult), numOfWorkpiece + "", containerCount + "",
                formatDecimal(exeResult.equivalentContainerCount),
                formatPercent(exeResult.actualAverageUtilization) + "%",
                exeResult.priorityBoardCount + "", exeResult.ordinaryBoardCount + "",
                formatSeconds(exeResult.totalPackingTimeMs) + "s"};
    }

    /** 将初始排样、全局优化、插入与总排样时间写入结果文件。 */
    private static void writeSolveTiming(PrintWriter writer, ExecutionResult result) {
        writer.println("Initial packing time: " + formatSeconds(result.initialPackingTimeMs) + "s");
        writer.println("Actual optimization time: " + formatSeconds(result.totalSolveTimeMs) + "s");
        writer.println("Optimization time scope: global repacking only; initial packing and ordinary insertion excluded.");
        writer.println("Priority solve time: " + formatSeconds(result.prioritySolveTimeMs) + "s");
        writer.println("Priority optimize time: " + formatSeconds(result.priorityOptimizeTimeMs) + "s");
        writer.println("Ordinary insertion time: " + formatSeconds(result.ordinaryInsertionTimeMs) + "s");
        writer.println("Ordinary solve time: " + formatSeconds(result.ordinarySolveTimeMs) + "s");
        writer.println("Ordinary optimize time: " + formatSeconds(result.ordinaryOptimizeTimeMs) + "s");
        writer.println("Total packing time (including ordinary insertion): "
                + formatSeconds(result.totalPackingTimeMs) + "s");
    }

    private static String formatSeconds(long timeMs) {
        return String.format(Locale.ROOT, "%.3f", timeMs / 1000.0);
    }

    /**
     * 将比率形式的利用率转换为固定小数位的百分数文本，不附加百分号。
     *
     * @param utilization 取值通常为 [0, 1] 的利用率比率。
     * @return 适合写入结果文本或控制台的百分数数值。
     */
    private static String formatPercent(double utilization) {
        return String.format(Locale.ROOT, "%.4f", utilization * 100.0);
    }

    /**
     * 将统计数值格式化为固定小数位文本。
     *
     * @param value 待输出的统计数值。
     * @return 保留四位小数的文本。
     */
    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
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

}
