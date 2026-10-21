package org.example.beamsearch.application;

import org.example.beamsearch.common.*;
import org.example.beamsearch.lb.entity.Item;
import org.example.beamsearch.lb.solver.RectangleLowerBoundSolver;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LoadingTestRun {

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
        return runPacking(materialInPath, workPieceInPath, outPath);
    }

    /**
     * 保留 CLASSData 矩形入口的兼容方法。
     *
     * <p>矩形和混合案例共用同一个受限初始排样流程，避免入口之间的策略分叉。</p>
     */
    public static String[] runRectangleWithImprove(String materialInPath,
                                                   String workPieceInPath,
                                                   String outPath) throws IOException {
        return runWithImprove(materialInPath, workPieceInPath, outPath);
    }

    /** 纯矩形入口显式传入统一旋转条件；其他入口不会进入案例下界计算。 */
    public static String[] runRectangleWithImprove(String materialInPath,
                                                   String workPieceInPath,
                                                   String outPath,
                                                   boolean allowRotation) throws IOException {
        return runPacking(materialInPath, workPieceInPath, outPath, allowRotation);
    }

    private static String[] runPacking(String materialInPath,
                                       String workPieceInPath,
                                       String outPath) throws IOException {
        return runPacking(materialInPath, workPieceInPath, outPath, null);
    }

    private static String[] runPacking(String materialInPath,
                                       String workPieceInPath,
                                       String outPath,
                                       Boolean rectangleRotation) throws IOException {
        long startTime = System.currentTimeMillis();
        // 每次调用独立读取 Agent 分档预算；未设置实验覆盖值时仍使用 baseline 的 600 秒。
        long totalSolveTimeMs = PackingRuntimeConfig.totalSolveTimeMs();

        ProblemLoader problemLoader = new ProblemLoader(workPieceInPath);
        problemLoader.loadContainer(materialInPath);
        ArrayList<Instance> instances = problemLoader.LoadInstancesFromCsv(true, outPath);
        if (instances.size() == 0) {
            if (rectangleRotation != null) {
                throw new IOException("纯矩形案例没有完整的可排样工件，不能计算案例下界。");
            }
            return null;
        }

        System.out.println("Start " + workPieceInPath + "," + " numOfColor " + instances.size());
        PrintStream oldout = System.out;
        Path oPath = Paths.get(outPath);
        String logPath = oPath.resolve("optimized.log").toString().replace("\\", "/");
        PrintStream out = new PrintStream(new FileOutputStream(logPath), true, "UTF-8");
        System.setOut(out);
        try {
            return writePackingResult(
                    instances,
                    totalSolveTimeMs,
                    oPath,
                    workPieceInPath,
                    startTime,
                    oldout,
                    rectangleRotation);
        } finally {
            // 分档实验会在同一 JVM 连续排样。正常/异常退出都关闭日志，避免 Windows 文件锁泄漏；
            // 此处只管理输出资源，不修改 baseline 的排序、预算或求解算法。
            System.setOut(oldout);
            out.close();
        }
    }

    /** 保留原求解及结果写出顺序，日志生命周期由外层统一管理。 */
    private static String[] writePackingResult(ArrayList<Instance> instances, long totalSolveTimeMs,
                                               Path oPath, String workPieceInPath, long startTime,
                                               PrintStream oldout, Boolean rectangleRotation) throws IOException {
        int numOfWorkpiece = 0;
        for (Instance instance : instances) {
            numOfWorkpiece = numOfWorkpiece + instance.totalBoxCount;
        }
        System.out.println("total number of nestable workpieces：" + numOfWorkpiece);

        // 先求解优先件，再把优先件板材的剩余空间交给普通件。
        // 600 秒由 PriorityFirstPacker 在优先件和普通件全局重排阶段之间统一分配；
        // 初始排样和普通件插入 Sp 独立计时，不扣减该预算。
        System.out.println("Start priority-first combined packing.");
        RectangleLowerBoundSolver.Result caseLowerBound = rectangleRotation == null ? null
                : calculateRectangleLowerBound(instances, workPieceInPath, rectangleRotation);
        ExecutionResult exeResult = caseLowerBound == null
                ? PriorityFirstPacker.solveWithTotalTime(instances, totalSolveTimeMs)
                : PriorityFirstPacker.solveRectangleWithTotalTime(instances, totalSolveTimeMs, caseLowerBound);
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

        File dir = oPath.resolve("orderId").toFile();
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
        if (exeResult.rectangleLowerBound != null) {
            pwTotal.println("Rectangle case lower bound: " + exeResult.rectangleLowerBound.lowerBound());
            pwTotal.println("Rectangle allow rotation: " + exeResult.rectangleLowerBound.allowRotation());
            pwTotal.println("Lower-bound calculation time: "
                    + formatSeconds(exeResult.rectangleLowerBound.elapsedNanos() / 1_000_000L) + "s");
            pwTotal.println("Board gap to lower bound: "
                    + (containerCount - exeResult.rectangleLowerBound.lowerBound()));
            pwTotal.println("Optimization stop reason: " + exeResult.priorityRepackStatistics.stopReason);
        }
        pwTotal.println("Running time: " + ((System.currentTimeMillis() - startTime) / 1000d) + "s");

        pw.close();
        pwStatistics.close();
        pwTotal.close();

        // 每次排样完成后写出同目录的 JSON 报告，供结果分析 Agent 直接读取。
        // 写报告只读取最终结果，不影响既有 CSV、日志或算法决策。
        Path runReportPath = RunReportWriter.write(
                oPath,
                oPath.getFileName() == null ? "unknown" : oPath.getFileName().toString(),
                exeResult,
                numOfWorkpiece,
                workpieceNum);
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
        System.out.println("运行报告: " + runReportPath.toAbsolutePath());
        if (exeResult.rectangleLowerBound != null) {
            System.out.println("矩形案例下界: " + exeResult.rectangleLowerBound.lowerBound()
                    + ", 当前板数: " + containerCount
                    + ", 停止原因: " + exeResult.priorityRepackStatistics.stopReason);
        }
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

    /** 按原始案例计算一次，拒绝将被过滤或方向不一致的输入当成完整案例。 */
    private static RectangleLowerBoundSolver.Result calculateRectangleLowerBound(
            ArrayList<Instance> instances, String workPieceInPath, boolean allowRotation) throws IOException {
        if (instances.size() != 1) {
            throw new IOException("纯矩形下界入口只接受同一板材、统一优先级的单案例。");
        }
        Instance instance = instances.get(0);
        Item[] items = Item.getItems(new File(workPieceInPath));
        if (items.length == 0 || items.length != instance.totalBoxCount) {
            throw new IOException("纯矩形输入存在被过滤的工件，不能对不完整案例判定达到下界。");
        }
        for (Item item : items) {
            if (item.rotatable != allowRotation) {
                throw new IOException("工件旋转标志与 RectanglePackingApplication.ALLOW_ROTATION 不一致。");
            }
        }
        for (Box box : instance.boxes) {
            if (Item.scale(box.length) != box.size[0] || Item.scale(box.width) != box.size[1]) {
                throw new IOException("下界与排样的内部尺寸不一致。");
            }
        }
        RectangleLowerBoundSolver.Result bound = RectangleLowerBoundSolver.solveDetailed(
                instance.length, instance.width, items, allowRotation, false);
        System.out.printf(Locale.ROOT,
                "Rectangle case lower bound=%d, allow rotation=%s, items=%d, calculation=%.3fms.%n",
                bound.lowerBound(), allowRotation, items.length, bound.elapsedNanos() / 1_000_000.0);
        return bound;
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
