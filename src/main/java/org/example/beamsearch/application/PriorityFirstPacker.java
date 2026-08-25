package org.example.beamsearch.application;

import org.example.beamsearch.algo.BeamSearch;
import org.example.beamsearch.common.Box;
import org.example.beamsearch.common.Container;
import org.example.beamsearch.common.ExecutionResult;
import org.example.beamsearch.common.Instance;
import org.example.beamsearch.common.Space;
import org.example.beamsearch.common.SpaceComparator;
import org.example.beamsearch.spacemanager.SpaceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 优先件先排的三阶段排样协调器。
 *
 * <p>这个类只负责组织求解阶段，不实现新的几何算法：</p>
 *
 * <ol>
 *     <li>使用现有 BeamSearch 排样优先件，并执行跨板材全局重排；</li>
 *     <li>锁定优化后的优先件布局，把剩余空间交给 BeamSearch 插入普通件；</li>
 *     <li>将还未放置的普通件交给普通的逐板 BeamSearch，并再次优化普通件板材。</li>
 * </ol>
 *
 * <p>优先件布局在第二阶段被视为固定布局，因此第二阶段不会移动或重新
 * 选择优先件。优先件与普通件必须使用相同尺寸的物理板材；如果不同，
 * 就不能放入同一个混合 Instance。</p>
 */
public final class PriorityFirstPacker {

    /** 桥接层约定：1 表示优先件，0 表示普通件。 */
    private static final String PRIORITY_COLOR = "1";

    /** 默认的排样与解优化总预算，不包含 NFP 拼接和组块生成阶段。 */
    public static final long DEFAULT_TOTAL_SOLVE_TIME_MS = 600_000L;

    private PriorityFirstPacker() {
    }

    /**
     * 执行优先先排的完整流程。
     *
     * @param groupedInstances 当前输入按颜色拆出的 Instance 列表
     * @param totalTimeMs 本次排样和解优化的总时间，单位为毫秒
     * @return 合并后的最终排样结果
     */
    public static ExecutionResult solve(List<Instance> groupedInstances,
                                        int totalTimeMs) {
        // 保留原有 int 入口，避免其他调用方需要同时修改；参数现在表示
        // 整个流程的总预算，而不是每张板材的重复预算。
        return solveWithTotalTime(groupedInstances, Math.max(1L, totalTimeMs));
    }

    /**
     * 执行优先先排的完整流程，并在所有阶段之间共享一个总截止时间。
     *
     * <p>阶段顺序和时间分配如下：</p>
     * <ol>
     *     <li>优先件新板求解，直到当前全局截止时间；</li>
     *     <li>按优先件数量占比的 0.8 次幂分配优先件全局优化时间；</li>
     *     <li>在已经确定的优先件板材中插入普通件；</li>
     *     <li>为剩余普通件开新板求解，并用最后剩余时间优化这些新板。</li>
     * </ol>
     *
     * <p>普通件插入阶段只修改板材内的普通件布局，不会增加或减少第一阶段
     * 已经确定的优先件板材，因此 Sp 在第二阶段保持不变。</p>
     *
     * @param groupedInstances 当前输入按颜色拆出的 Instance 列表
     * @param totalTimeMs 排样和解优化的总时间，单位为毫秒
     * @return 合并后的最终排样结果，并包含各阶段实际耗时
     */
    public static ExecutionResult solveWithTotalTime(List<Instance> groupedInstances,
                                                     long totalTimeMs) {
        if (groupedInstances == null || groupedInstances.isEmpty()) {
            return emptyResult();
        }

        List<Box> allBoxes = collectBoxes(groupedInstances);
        if (allBoxes.isEmpty()) {
            return emptyResult();
        }

        Instance firstInstance = findFirstInstance(groupedInstances);
        if (firstInstance == null) {
            return emptyResult();
        }
        validateSameBoardSize(groupedInstances, firstInstance);

        Container mixedContainer = new Container(
                "mixed",
                firstInstance.length / 10.0,
                firstInstance.width / 10.0,
                0);

        List<Box> priorityBoxes = new ArrayList<>();
        for (Box box : allBoxes) {
            if (isPriorityBox(box)) {
                priorityBoxes.add(box);
            }
        }

        // 没有优先件时只执行普通件的新板排样和普通件全局优化，
        // 避免构造无意义的优先件种子板材。
        if (priorityBoxes.isEmpty()) {
            Instance ordinaryInstance = new Instance(new ArrayList<>(allBoxes), mixedContainer);
            // 时间预算从真正开始排样的时刻开始计算，因此不包含前面的
            // NFP 拼接、组块转换以及 Instance 整理工作。
            long solveStartNanos = System.nanoTime();
            long totalBudgetMs = Math.max(1L, totalTimeMs);
            long deadlineMillis = System.currentTimeMillis() + totalBudgetMs;

            long ordinarySolveStartNanos = System.nanoTime();
            ExecutionResult ordinaryResult = solveNewBoardsUntil(
                    ordinaryInstance,
                    deadlineMillis);
            long ordinarySolveTimeMs = elapsedMillis(ordinarySolveStartNanos);

            long ordinaryOptimizeStartNanos = System.nanoTime();
            ordinaryResult = GlobalRepackOptimizer.optimize(
                    ordinaryInstance,
                    ordinaryResult,
                    remainingMillis(deadlineMillis));
            long ordinaryOptimizeTimeMs = elapsedMillis(ordinaryOptimizeStartNanos);

            ordinaryResult.priorityBoardCount = 0;
            ordinaryResult.ordinaryBoardCount = ordinaryResult.solutions.size();
            setTiming(ordinaryResult,
                    0,
                    0,
                    0,
                    ordinarySolveTimeMs,
                    ordinaryOptimizeTimeMs,
                    elapsedMillis(solveStartNanos));
            return ordinaryResult;
        }

        // 第一阶段只建立优先件 Instance。Box 对象仍然复用同一份，便于
        // 后面把优先件放置记录转换到混合 Instance 中。
        Instance priorityInstance = new Instance(new ArrayList<>(priorityBoxes), mixedContainer);
        // 优先件 Instance 建立完成后才启动总时钟，确保统计范围只覆盖
        // 排样和解优化，不把前面的 NFP 或输入整理时间算进去。
        long solveStartNanos = System.nanoTime();
        long totalBudgetMs = Math.max(1L, totalTimeMs);
        long deadlineMillis = System.currentTimeMillis() + totalBudgetMs;
        long priorityWorkpieceCount = countWorkpieces(priorityBoxes);
        long totalWorkpieceCount = countWorkpieces(allBoxes);

        long prioritySolveStartNanos = System.nanoTime();
        ExecutionResult priorityResult = solveNewBoardsUntil(
                priorityInstance,
                deadlineMillis);
        long prioritySolveTimeMs = elapsedMillis(prioritySolveStartNanos);

        // 目标函数首先最小化 Sp，因此必须在普通件插入前完成优先件全局优化。
        // 优化结束后 GlobalRepackOptimizer 会重新生成 boardStates，第二阶段
        // 只接收这批最终优先件板材，不会因为普通件插入而新增优先件板材。
        long priorityOptimizeTimeLimitMs = calculatePriorityOptimizeTime(
                Math.min(
                        Math.max(0L, totalBudgetMs - prioritySolveTimeMs),
                        remainingMillis(deadlineMillis)),
                priorityWorkpieceCount,
                totalWorkpieceCount);
        long priorityOptimizeStartNanos = System.nanoTime();
        priorityResult = GlobalRepackOptimizer.optimize(
                priorityInstance,
                priorityResult,
                priorityOptimizeTimeLimitMs);
        long priorityOptimizeTimeMs = elapsedMillis(priorityOptimizeStartNanos);

        // 重新建立混合 Instance，统一重编号 typeNum，使 freeBoxes 和
        // GeneralBlock.typeCount 在后续阶段使用同一套下标。
        Instance mixedInstance = new Instance(new ArrayList<>(allBoxes), mixedContainer);
        boolean[] ordinaryTypes = buildOrdinaryTypeMask(mixedInstance);

        BeamSearch insertionSearch = new BeamSearch(
                createSpaceManager(mixedInstance),
                mixedInstance);
        long insertionStartNanos = System.nanoTime();
        ExecutionResult insertionResult = insertionSearch.packIntoExistingBoardsUntil(
                priorityResult.boardStates,
                ordinaryTypes,
                deadlineMillis);
        long insertionTimeMs = elapsedMillis(insertionStartNanos);

        // 第二阶段只允许在已有优先件板材中继续排样，板材数量必须与
        // 第一阶段优化后的快照数量一致。这个检查用于防止后续改动意外改变 Sp。
        if (insertionResult.solutions.size() != priorityResult.boardStates.size()) {
            throw new IllegalStateException(
                    "Ordinary insertion must not change the priority board count.");
        }

        // insertionResult.unplacedCounts 的下标属于 mixedInstance，使用它
        // 重新建立只包含剩余普通件的 Instance，继续开新板排样。
        Instance remainingOrdinaryInstance = createRemainingInstance(
                mixedInstance,
                insertionResult.unplacedCounts,
                ordinaryTypes,
                mixedContainer);

        ExecutionResult remainingResult = null;
        long ordinarySolveTimeMs = 0;
        long ordinaryOptimizeTimeMs = 0;
        if (remainingOrdinaryInstance != null) {
            long ordinarySolveStartNanos = System.nanoTime();
            remainingResult = solveNewBoardsUntil(
                    remainingOrdinaryInstance,
                    deadlineMillis);
            ordinarySolveTimeMs = elapsedMillis(ordinarySolveStartNanos);

            // 这里只优化普通件新开的板材。由于优先件板材已经在上一步锁定，
            // 该优化不会改变 Sp，只会尽量减少 So。
            long ordinaryOptimizeStartNanos = System.nanoTime();
            remainingResult = GlobalRepackOptimizer.optimize(
                    remainingOrdinaryInstance,
                    remainingResult,
                    remainingMillis(deadlineMillis));
            ordinaryOptimizeTimeMs = elapsedMillis(ordinaryOptimizeStartNanos);
        }

        ExecutionResult finalResult = mergeResults(
                priorityResult,
                priorityInstance,
                insertionResult,
                remainingResult,
                remainingOrdinaryInstance,
                mixedInstance);
        setTiming(finalResult,
                prioritySolveTimeMs,
                priorityOptimizeTimeMs,
                insertionTimeMs,
                ordinarySolveTimeMs,
                ordinaryOptimizeTimeMs,
                elapsedMillis(solveStartNanos));
        return finalResult;
    }

    /** 在全局截止时间前求解一个独立 Instance 的新板排样。 */
    private static ExecutionResult solveNewBoardsUntil(Instance instance,
                                                       long deadlineMillis) {
        Comparator<Space> comparator = SpaceComparator.getSpaceComparator(instance, 1);
        SpaceManager spaceManager = new SpaceManager(comparator);
        BeamSearch beamSearch = new BeamSearch(spaceManager, instance);

        int minCon = 0;
        long boardArea = (long) instance.length * instance.width;
        if (boardArea > 0) {
            minCon = (int) (instance.totalBoxVolume / boardArea);
        }

        ExecutionResult result = beamSearch.solveUntil(deadlineMillis, minCon);
        result.setAvgUtilization();
        return result;
    }

    private static List<Box> collectBoxes(List<Instance> instances) {
        List<Box> boxes = new ArrayList<>();
        for (Instance instance : instances) {
            if (instance == null || instance.boxes == null) {
                continue;
            }
            boxes.addAll(Arrays.asList(instance.boxes));
        }
        return boxes;
    }

    /** 找到第一个有效 Instance，避免输入列表中偶然存在 null 时启动失败。 */
    private static Instance findFirstInstance(List<Instance> instances) {
        for (Instance instance : instances) {
            if (instance != null) {
                return instance;
            }
        }
        return null;
    }

    /**
     * 统计矩形化后的实际工件数量，而不是 Box 类型数量。
     * Box.count 可能代表同一矩形类型的多个工件，时间比例必须按工件数计算。
     */
    private static long countWorkpieces(List<Box> boxes) {
        long count = 0;
        for (Box box : boxes) {
            if (box != null) {
                count += Math.max(0, box.count);
            }
        }
        return count;
    }

    /**
     * 按“剩余总预算 × 优先件数量占比的 0.8 次幂”计算优先件优化预算。
     * maxAvailableMs 用于同时遵守 System.currentTimeMillis() 的绝对截止时间。
     */
    private static long calculatePriorityOptimizeTime(long maxAvailableMs,
                                                       long priorityWorkpieceCount,
                                                       long totalWorkpieceCount) {
        if (maxAvailableMs <= 0
                || priorityWorkpieceCount <= 0
                || totalWorkpieceCount <= 0) {
            return 0;
        }

        double priorityRatio = (double) priorityWorkpieceCount / totalWorkpieceCount;
        priorityRatio = Math.min(1.0, Math.max(0.0, priorityRatio));
        double allocatedTime = maxAvailableMs * Math.pow(priorityRatio, 0.8);
        return Math.max(0, Math.min(maxAvailableMs, (long) allocatedTime));
    }

    /** 返回距全局截止时间的剩余毫秒数，不返回负数。 */
    private static long remainingMillis(long deadlineMillis) {
        return Math.max(0L, deadlineMillis - System.currentTimeMillis());
    }

    /** 使用单调时钟统计阶段实际耗时，避免系统时间校准影响结果。 */
    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /** 将各阶段实际耗时写入最终结果，供日志和统计文件使用。 */
    private static void setTiming(ExecutionResult result,
                                  long prioritySolveTimeMs,
                                  long priorityOptimizeTimeMs,
                                  long ordinaryInsertionTimeMs,
                                  long ordinarySolveTimeMs,
                                  long ordinaryOptimizeTimeMs,
                                  long totalSolveTimeMs) {
        result.prioritySolveTimeMs = prioritySolveTimeMs;
        result.priorityOptimizeTimeMs = priorityOptimizeTimeMs;
        result.ordinaryInsertionTimeMs = ordinaryInsertionTimeMs;
        result.ordinarySolveTimeMs = ordinarySolveTimeMs;
        result.ordinaryOptimizeTimeMs = ordinaryOptimizeTimeMs;
        result.totalSolveTimeMs = totalSolveTimeMs;
    }

    private static void validateSameBoardSize(List<Instance> instances,
                                              Instance firstInstance) {
        for (Instance instance : instances) {
            if (instance == null) {
                continue;
            }
            if (instance.length != firstInstance.length
                    || instance.width != firstInstance.width) {
                throw new IllegalArgumentException(
                        "Priority and ordinary items must use the same board size.");
            }
        }
    }

    private static boolean[] buildOrdinaryTypeMask(Instance instance) {
        boolean[] ordinaryTypes = new boolean[instance.boxes.length];
        for (int i = 0; i < instance.boxes.length; i++) {
            ordinaryTypes[i] = !isPriorityBox(instance.boxes[i]);
        }
        return ordinaryTypes;
    }

    private static boolean isPriorityBox(Box box) {
        return PRIORITY_COLOR.equals(box.color)
                || "true".equalsIgnoreCase(box.color);
    }

    private static SpaceManager createSpaceManager(Instance instance) {
        Comparator<Space> comparator = SpaceComparator.getSpaceComparator(instance, 1);
        return new SpaceManager(comparator);
    }

    private static Instance createRemainingInstance(Instance mixedInstance,
                                                     int[] remainingCounts,
                                                     boolean[] ordinaryTypes,
                                                     Container container) {
        if (remainingCounts == null) {
            return null;
        }

        List<Box> remainingBoxes = new ArrayList<>();
        for (int i = 0; i < mixedInstance.boxes.length; i++) {
            if (!ordinaryTypes[i] || remainingCounts[i] <= 0) {
                continue;
            }

            // 继续复用原 Box，使最终输出仍能找到对应的 NFP Block ID。
            // count 只表示这一阶段还需要排多少个，不会改变几何属性。
            Box box = mixedInstance.boxes[i];
            box.count = remainingCounts[i];
            remainingBoxes.add(box);
        }

        if (remainingBoxes.isEmpty()) {
            return null;
        }
        return new Instance(new ArrayList<>(remainingBoxes), container);
    }

    private static ExecutionResult mergeResults(ExecutionResult priorityResult,
                                                Instance priorityInstance,
                                                ExecutionResult insertedResult,
                                                ExecutionResult remainingResult,
                                                Instance remainingInstance,
                                                Instance mixedInstance) {
        ExecutionResult finalResult = new ExecutionResult();

        if (insertedResult != null) {
            finalResult.solutions.addAll(insertedResult.solutions);
            finalResult.boardStates.addAll(insertedResult.boardStates);
        }
        if (remainingResult != null) {
            finalResult.solutions.addAll(remainingResult.solutions);
            finalResult.boardStates.addAll(remainingResult.boardStates);
        }

        // insertedResult 的每张板材都来自第一阶段的优先件快照，因此这里的
        // priorityBoardCount 就是最终 Sp；remainingResult 的数量就是 So。
        finalResult.priorityBoardCount = insertedResult == null
                ? 0
                : insertedResult.solutions.size();
        finalResult.ordinaryBoardCount = remainingResult == null
                ? 0
                : remainingResult.solutions.size();

        // 三个阶段使用过不同的 Instance 下标。统一按 Box 对象映射到
        // mixedInstance，避免优先件或第三阶段未排件的数量被覆盖或丢失。
        fillFinalUnplacedBoxes(
                finalResult,
                priorityResult,
                priorityInstance,
                insertedResult,
                remainingResult,
                remainingInstance,
                mixedInstance);
        finalResult.setAvgUtilization();
        return finalResult;
    }

    private static void fillFinalUnplacedBoxes(ExecutionResult finalResult,
                                                ExecutionResult priorityResult,
                                                Instance priorityInstance,
                                                ExecutionResult insertedResult,
                                                ExecutionResult remainingResult,
                                                Instance remainingInstance,
                                                Instance mixedInstance) {
        int[] finalCounts = new int[mixedInstance.boxes.length];

        // 插入阶段的结果已经包含普通件在优先件板材之后的剩余数量。
        copyCountsByBox(finalCounts, mixedInstance, insertedResult, mixedInstance);

        // 优先阶段的结果使用 priorityInstance 下标，需要覆盖回混合实例。
        copyCountsByBox(finalCounts, mixedInstance, priorityResult, priorityInstance);

        // 第三阶段可能又排掉了一部分普通件，因此最后用第三阶段的数量覆盖
        // 同一批普通 Box；如果没有第三阶段，则保留插入阶段的结果。
        if (remainingResult != null && remainingInstance != null) {
            copyCountsByBox(finalCounts, mixedInstance, remainingResult, remainingInstance);
        }

        finalResult.unplacedCounts = finalCounts;
        finalResult.unplacedBoxes = new ArrayList<>();
        finalResult.unplacedBoxesVol = 0;
        for (int i = 0; i < finalCounts.length; i++) {
            if (finalCounts[i] <= 0) {
                continue;
            }
            Box box = mixedInstance.boxes[i];
            finalResult.unplacedBoxes.add(box);
            finalResult.unplacedBoxesVol += box.volume * finalCounts[i];
        }
    }

    private static void copyCountsByBox(int[] targetCounts,
                                        Instance targetInstance,
                                        ExecutionResult sourceResult,
                                        Instance sourceInstance) {
        if (sourceResult == null
                || sourceResult.unplacedCounts == null
                || sourceInstance == null) {
            return;
        }

        for (int sourceIndex = 0;
             sourceIndex < sourceInstance.boxes.length
                     && sourceIndex < sourceResult.unplacedCounts.length;
             sourceIndex++) {
            int targetIndex = findBoxIndex(targetInstance, sourceInstance.boxes[sourceIndex]);
            if (targetIndex >= 0) {
                targetCounts[targetIndex] = sourceResult.unplacedCounts[sourceIndex];
            }
        }
    }

    private static int findBoxIndex(Instance instance, Box targetBox) {
        for (int i = 0; i < instance.boxes.length; i++) {
            if (instance.boxes[i] == targetBox) {
                return i;
            }
        }
        return -1;
    }

    private static ExecutionResult emptyResult() {
        ExecutionResult result = new ExecutionResult();
        result.unplacedBoxes = new ArrayList<>();
        result.unplacedCounts = new int[0];
        return result;
    }
}
