package org.example.beamsearch.algo;

import org.example.beamsearch.blockgenerator.BlockGenerator;
import org.example.beamsearch.application.PackingRuntimeConfig;
import org.example.beamsearch.blockgenerator.GeneralBlock;
import org.example.beamsearch.common.*;
import org.example.beamsearch.spacemanager.SpaceManager;
import org.example.beamsearch.state.State;

import java.util.*;

public class BeamSearch {
    private final SpaceManager spaceManager;
    final Instance inst;

    /**
     * 连续完成多少轮“全部低利用率板材均未减少板材数量”后停止全局重排。
     * 该值只作为无改进保护，真正的主停止条件仍然是 maxTime。
     */
    private static final int MAX_NO_IMPROVEMENT_SWEEPS = 20;

    /** 按本阶段初始板数决定是否允许扩张；后续部分搬运不重置无减板轮数。 */
    private static final int MIN_INITIAL_BOARDS_FOR_EXPANSION = 10;
    private static final int NO_REDUCTION_SWEEPS_BEFORE_EXPANSION = 2;
    private static final int BASE_RECEIVER_LIMIT = 2;
    private static final int EXPANDED_RECEIVER_LIMIT = 3;
    /** 三板组合采用有界抽样，避免枚举所有三元组放大优化耗时。 */
    private static final int TRIPLE_RECEIVER_POOL_SIZE = 8;
    private static final int MAX_TRIPLE_CANDIDATES = 24;
    private static final long TRIPLE_REPACK_TIME_LIMIT_MS = 500;

    private long finishTime = 0;
    /** 最近一次 ImproveByRepack 的只读监控结果。 */
    private RepackStatistics lastRepackStatistics = RepackStatistics.notRun("not_called");

    /**
     * 创建使用原有固定启发式候选排序的 Beam Search 求解器。
     *
     * @param spaceManager 初始板材空闲空间管理器。
     * @param inst 当前待排样实例。
     */
    public BeamSearch(SpaceManager spaceManager, Instance inst) {
        this.inst = inst;
        this.spaceManager = spaceManager;
    }

    /** 返回最近一次全局重排的统计数据，供运行报告汇总使用。 */
    public RepackStatistics getLastRepackStatistics() {
        return lastRepackStatistics;
    }

    /**
     * 兼容旧调用方的相对时间入口。
     *
     * <p>新流程应优先使用 {@link #solveUntil(long, int)}，这样多个阶段
     * 可以共享同一个全局截止时间。这里保留原方法，避免其他入口的调用
     * 方式被本次时间调度修改破坏。</p>
     *
     * @param timeLimit 本次求解允许使用的时间，单位为毫秒
     * @param minCon 原有的最低装载约束参数
     * @return 本次求解结果
     */
    public ExecutionResult solve(int timeLimit, int minCon) {
        long deadlineMillis = System.currentTimeMillis() + Math.max(1, timeLimit);
        return solveUntil(deadlineMillis, minCon);
    }

    /**
     * 在指定的绝对截止时间之前逐板求解普通新板排样。
     *
     * <p>原实现会给每一张板材重复分配同样的 timeLimit，板材数量增加时
     * 总耗时也会线性增加。本方法把截止时间传入逐板循环，每张板材只使用
     * 当前全局剩余时间，从而让优先件求解或普通件求解不会越过总预算。</p>
     *
     * @param deadlineMillis 全局绝对截止时间，使用 System.currentTimeMillis() 的时间基准
     * @param minCon 原有的最低装载约束参数
     * @return 截止时间前得到的本次求解结果
     */
    public ExecutionResult solveUntil(long deadlineMillis, int minCon) {
        ExecutionResult exeResult = new ExecutionResult();
        int containerNum = 1;
        State endState = null;
        long start = System.currentTimeMillis();

        // 后续阶段可能在进入本方法前已经耗尽全局预算。此时直接返回
        // 初始未排数量，避免为了生成候选块又额外消耗无效时间。
        if (System.currentTimeMillis() >= deadlineMillis) {
            int[] initialFreeBoxes = new int[inst.boxes.length];
            for (int i = 0; i < inst.boxes.length; i++) {
                initialFreeBoxes[i] = inst.boxes[i].count;
            }
            fillUnplacedBoxes(exeResult, initialFreeBoxes, null);
            return exeResult;
        }

        GeneralBlock[] allBlocks = new BlockGenerator(inst).generateSingleBlock(true);
        while (allBlocks.length > 0 && System.currentTimeMillis() < deadlineMillis) {
            System.out.println("Start nesting on the " + containerNum + "th large board, remaining blocks count: " + allBlocks.length);
            State initialState;
            if (containerNum == 1) {
                initialState = State.createInitState(inst, spaceManager, allBlocks);
            } else {
                initialState = State.initState(endState, spaceManager);
            }

            // 当前板材只使用全局截止时间前的剩余时间，避免逐板重复计时。
            long remainingTime = deadlineMillis - System.currentTimeMillis();
            int boardTime = (int) Math.min(Integer.MAX_VALUE, Math.max(1, remainingTime));

            // 把单张板材的搜索抽成独立方法，后续阶段可以从已有空间继续搜索。
            endState = searchOneBoard(
                    initialState,
                    boardTime,
                    minCon,
                    0,
                    PackingRuntimeConfig.maxBeamWidth());

            allBlocks = endState.availableBlocks;

            exeResult.solution = endState.toSolution();

            exeResult.solutions.add(exeResult.solution);
            exeResult.boardStates.add(createBoardStateSnapshot(exeResult.solution));
            exeResult.unplacedBoxes = new ArrayList<Box>();
            System.out.println("Utilization of the " + containerNum + "th large board: " + exeResult.solution.getUtilization() + ", Time taken for this board: "
                    + ((System.currentTimeMillis() - start) * 0.001) + "s");
            System.out.println("-------------------------------------------------------------------");
            containerNum++;
        }

        // 原有 solve() 只返回 solutions，没有保存最后一张板材结束后的
        // freeBoxes。统一回填后，调用方可以判断是否仍有工件未排入新板。
        if (endState != null) {
            fillUnplacedBoxes(exeResult, endState.getFreeBoxes(), null);
        } else {
            int[] initialFreeBoxes = new int[inst.boxes.length];
            for (int i = 0; i < inst.boxes.length; i++) {
                initialFreeBoxes[i] = inst.boxes[i].count;
            }
            fillUnplacedBoxes(exeResult, initialFreeBoxes, null);
        }

        return exeResult;
    }

    /**
     * 使用固定的单板预算完成全部新板排样。
     *
     * <p>时间限制只约束一张板的 Beam 扩展，而不会中止整个案例；即使某张板的
     * 受限搜索没有产生放置，也会退回到确定性的贪心装载，保证所有可放工件
     * 仍能继续排完。矩形、优先件、普通件和 NFP 组块的新板阶段共用本方法。</p>
     *
     * @param perBoardTimeMs 每张新板的 Beam Search 预算，单位为毫秒
     * @param minCon 原有的最低装载约束参数
     * @param maxBeamWidth 本次搜索可达到的最大 Beam 宽度
     * @return 完整排样结果；不可放入任何板材的输入会明确失败，而不会生成空板循环
     */
    public ExecutionResult solveWithPerBoardTimeLimit(long perBoardTimeMs,
                                                       int minCon,
                                                       int maxBeamWidth) {
        ExecutionResult exeResult = new ExecutionResult();
        int containerNum = 1;
        State endState = null;
        long start = System.currentTimeMillis();
        int boardTime = toPositiveIntMillis(perBoardTimeMs);
        int boundedBeamWidth = Math.max(4, maxBeamWidth);

        GeneralBlock[] allBlocks = new BlockGenerator(inst).generateSingleBlock(true);
        while (allBlocks.length > 0) {
            System.out.println("Start nesting on the " + containerNum
                    + "th large board, remaining blocks count: " + allBlocks.length
                    + ", per-board search budget: " + boardTime + "ms, max beam width: "
                    + boundedBeamWidth);
            State initialState = containerNum == 1
                    ? State.createInitState(inst, spaceManager, allBlocks)
                    : State.initState(endState, spaceManager);

            endState = searchOneBoard(
                    initialState,
                    boardTime,
                    minCon,
                    0,
                    boundedBeamWidth);
            if (!hasPlacedWorkpiece(initialState, endState)) {
                endState = completeBoardGreedily(initialState);
            }
            if (!hasPlacedWorkpiece(initialState, endState)) {
                throw new IllegalStateException(
                        "Initial packing could not place any remaining workpiece on a new board.");
            }

            allBlocks = endState.availableBlocks;
            exeResult.solution = endState.toSolution();
            exeResult.solutions.add(exeResult.solution);
            exeResult.boardStates.add(createBoardStateSnapshot(exeResult.solution));
            exeResult.unplacedBoxes = new ArrayList<>();
            System.out.println("Utilization of the " + containerNum + "th large board: "
                    + exeResult.solution.getUtilization() + ", Time taken for this board: "
                    + ((System.currentTimeMillis() - start) * 0.001) + "s");
            System.out.println("-------------------------------------------------------------------");
            containerNum++;
        }

        if (endState != null) {
            fillUnplacedBoxes(exeResult, endState.getFreeBoxes(), null);
        } else {
            fillUnplacedBoxes(exeResult, initialFreeBoxes(null), null);
        }
        return exeResult;
    }

    /** 将毫秒预算安全地收敛到 searchOneBoard 使用的 int 范围。 */
    private static int toPositiveIntMillis(long timeMs) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeMs));
    }

    /** 判断受限 Beam Search 是否至少成功放入了一件工件。 */
    private static boolean hasPlacedWorkpiece(State initialState, State candidateState) {
        if (initialState == null || candidateState == null) {
            return false;
        }
        int[] initialFreeBoxes = initialState.getFreeBoxes();
        int[] candidateFreeBoxes = candidateState.getFreeBoxes();
        int length = Math.min(initialFreeBoxes.length, candidateFreeBoxes.length);
        for (int index = 0; index < length; index++) {
            if (candidateFreeBoxes[index] < initialFreeBoxes[index]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当受限 Beam Search 在异常短预算内没有形成叶节点时，以原有最佳块排序
     * 完成当前板材。该路径只用于保证进度，不参与正常候选比较。
     */
    private State completeBoardGreedily(State initialState) {
        State state = initialState;
        while (state.hasFreeSpace()) {
            Space space = state.chooseBestSpace();
            GeneralBlock block = state.chooseBestBlock(space);
            state = block == null ? state.deleteSpace(space) : state.packBlock(space, block);
        }
        return state;
    }

    /**
     * 在已有优先件板材中插入普通件，并共享一个全局绝对截止时间。
     *
     * <p>普通件插入必须保持优先件板材数量不变，因此即使时间已经耗尽，
     * 也要把尚未处理的 seedBoard 原样复制到结果中。这样第三阶段可以
     * 正确继承 Sp，而不会因为超时丢失优先件板材。</p>
     *
     * @param seedBoards 优先件阶段产生的板材快照
     * @param allowedTypes 与 inst.boxes 对应的可继续排样类型
     * @param deadlineMillis 全局绝对截止时间，使用 System.currentTimeMillis() 的时间基准
     * @return 插入普通件后的板材结果
     */
    public ExecutionResult packIntoExistingBoardsUntil(List<BoardStateSnapshot> seedBoards,
                                                        boolean[] allowedTypes,
                                                        long deadlineMillis) {
        ExecutionResult result = new ExecutionResult();
        if (seedBoards == null || seedBoards.isEmpty()) {
            // 没有优先件 seedBoard 时，普通件仍然没有开始插入，必须把
            // 允许类型的完整剩余数量传给第三阶段，不能误报为已排完。
            fillUnplacedBoxes(result, initialFreeBoxes(allowedTypes), allowedTypes);
            return result;
        }

        int[] freeBoxes = initialFreeBoxes(allowedTypes);

        // 如果前一阶段已经耗尽总预算，不再生成普通件候选块，但仍然需要
        // 原样复制所有优先件 seedBoard，保证 Sp 的结构和数量不丢失。
        if (System.currentTimeMillis() >= deadlineMillis) {
            for (BoardStateSnapshot seedBoard : seedBoards) {
                Solution unchangedSolution = seedBoard.toSolution(inst);
                result.solutions.add(unchangedSolution);
                result.boardStates.add(createBoardStateSnapshot(unchangedSolution));
            }
            fillUnplacedBoxes(result, freeBoxes, allowedTypes);
            result.setAvgUtilization();
            return result;
        }

        return packByCandidateItemRepacking(new ArrayList<>(seedBoards), allowedTypes, deadlineMillis);
    }

    /**
     * 对一个初始 State 执行单张板材的集束搜索。
     */
    private State searchOneBoard(State initialState,
                                 int timeLimit,
                                 int minCon,
                                 int volumeType,
                                 int maxBeamWidth) {
        Node bestNode = new Node();
        long startTime = System.currentTimeMillis();
        finishTime = startTime + Math.max(1, timeLimit);

        int availableBlockCount = initialState.availableBlocks.length;
        for (int width = 4;
             width <= maxBeamWidth && System.currentTimeMillis() < finishTime;
             width <<= 1) {
            if (width > availableBlockCount / 3 && width != 4 && minCon > 330) {
                break;
            }

            Queue<Node> newNodes = new LinkedList<>();
            Node root = new Node();
            root.state = initialState;
            newNodes.add(root);

            if (System.currentTimeMillis() >= finishTime && width > 4) {
                break;
            }

            while (!newNodes.isEmpty()) {
                int limit = newNodes.size();
                TreeSet<Node> offspring = new TreeSet<>();

                if (System.currentTimeMillis() >= finishTime && width > 4) {
                    break;
                }

                for (int i = 0; i < limit; i++) {
                    if (System.currentTimeMillis() >= finishTime && width > 4) {
                        break;
                    }

                    Node currentNode = newNodes.poll();
                    ArrayList<Node> children = new ArrayList<>();
                    int childCount = currentNode.state.countPlacedBlock() == 0
                            ? width * width
                            : width;

                    blockSearch(currentNode.state, childCount, children, volumeType);

                    if (children.isEmpty()) {
                        if (currentNode.score >= bestNode.score) {
                            bestNode = currentNode;
                        }
                    } else {
                        for (Node child : children) {
                            update(offspring, width, child);
                        }
                    }
                }

                for (Node node : offspring) {
                    newNodes.add(node);
                }

                if (System.currentTimeMillis() >= finishTime && width > 4) {
                    break;
                }
            }

            if (width >= availableBlockCount
                    || width >= maxBeamWidth
                    || width > Integer.MAX_VALUE / 2) {
                break;
            }
        }

        // 没有可扩展候选时，root 仍然是一个有效状态。
        return bestNode.state == null ? initialState : bestNode.state;
    }

    /**
     * 使用“候选普通件整体重排”策略向既有 Sp 逐张插入普通件。
     *
     * <p>每次选择当前最大连续空闲区所在的 Sp，再依次尝试一个普通件。候选件先通过
     * 矩形占用面积上界：当前板材中全部已放工件面积加候选面积超过板材面积时必定失败；
     * 未超过时，把该 Sp 内所有优先件、已插入普通件及候选件一起重新进行单板排样。
     * 仅当全部工件重新装入同一张板材时，才提交新布局并扣减该普通件数量。</p>
     *
     * @param seedBoards 优先件阶段输出的 Sp 板材快照；其中可能已含此前成功插入的普通件。
     * @param allowedTypes 与 {@link #inst} 工件类型一一对应的普通件允许掩码。
     * @param deadlineMillis 当前案例排样的全局绝对截止时间，单位为毫秒。
     * @return 插入成功后的 Sp 布局及仍未插入的普通件数量。
     */
    private ExecutionResult packByCandidateItemRepacking(List<BoardStateSnapshot> seedBoards,
                                                          boolean[] allowedTypes,
                                                          long deadlineMillis) {
        ExecutionResult result = new ExecutionResult();
        int[] freeBoxes = initialFreeBoxes(allowedTypes);
        List<BoardStateSnapshot> remainingBoards = new ArrayList<>(seedBoards);

        while (!remainingBoards.isEmpty()) {
            if (System.currentTimeMillis() >= deadlineMillis) {
                appendUnchangedBoards(result, remainingBoards);
                break;
            }

            int boardIndex = findLargestResidualSpaceBoard(remainingBoards);
            BoardStateSnapshot selectedBoard = remainingBoards.remove(boardIndex);
            BoardStateSnapshot updatedBoard = tryInsertCandidatesByRepacking(
                    selectedBoard,
                    freeBoxes,
                    allowedTypes,
                    remainingBoards.size() + 1,
                    deadlineMillis);
            result.solutions.add(updatedBoard.toSolution(inst));
            result.boardStates.add(updatedBoard);
        }

        fillUnplacedBoxes(result, freeBoxes, allowedTypes);
        result.setAvgUtilization();
        return result;
    }

    /**
     * 将未处理的 Sp 原样写入插入结果，保证全局时间耗尽时优先件板材不会丢失。
     *
     * @param result 正在构建的插入阶段结果。
     * @param boards 尚未进行普通件插入的 Sp 板材快照。
     */
    private void appendUnchangedBoards(ExecutionResult result, List<BoardStateSnapshot> boards) {
        for (BoardStateSnapshot board : boards) {
            result.solutions.add(board.toSolution(inst));
            result.boardStates.add(board);
        }
    }

    /**
     * 在一张指定 Sp 中按普通件优先级逐件执行“面积筛选—整体重排—提交或拒绝”。
     *
     * @param original 当前选定的 Sp 板材快照。
     * @param freeBoxes 混合实例中每种普通件尚未使用的数量；成功时会原地扣减。
     * @param allowedTypes 普通件类型允许掩码。
     * @param remainingBoardCount 包含当前 Sp 在内、尚待处理的 Sp 数量，用于均分候选验证时间。
     * @param deadlineMillis 当前案例排样的全局绝对截止时间，单位为毫秒。
     * @return 已提交零个或多个普通件后的 Sp 快照；失败候选不会改变布局。
     */
    private BoardStateSnapshot tryInsertCandidatesByRepacking(BoardStateSnapshot original,
                                                              int[] freeBoxes,
                                                              boolean[] allowedTypes,
                                                              int remainingBoardCount,
                                                              long deadlineMillis) {
        BoardStateSnapshot currentBoard = original;
        Set<Integer> rejectedTypeIndexes = new HashSet<>();

        while (System.currentTimeMillis() < deadlineMillis) {
            CandidateItem candidate = nextCandidateItem(freeBoxes, allowedTypes, rejectedTypeIndexes);
            if (candidate == null) {
                break;
            }

            if (!passesCandidateAreaBound(currentBoard, candidate.box())) {
                rejectedTypeIndexes.add(candidate.typeIndex());
                continue;
            }

            long remainingCandidateCount = remainingCandidateItemCount(
                    freeBoxes, allowedTypes, rejectedTypeIndexes);
            long candidateDeadline = candidateRepackDeadline(
                    deadlineMillis, remainingCandidateCount, remainingBoardCount);
            BoardStateSnapshot repackedBoard = repackBoardWithCandidate(
                    currentBoard, candidate.box(), candidateDeadline);
            if (repackedBoard == null) {
                // 本候选已经在当前 Sp 的完整重排中失败；继续测试下一个普通件类型。
                rejectedTypeIndexes.add(candidate.typeIndex());
                continue;
            }

            currentBoard = repackedBoard;
            freeBoxes[candidate.typeIndex()]--;
            // 同一种普通件仍可能有多个副本。已成功一次后允许继续测试下一副本；
            // 先前失败的其他类型仍保持跳过，避免对同一布局反复做无效验证。
        }
        return currentBoard;
    }

    /**
     * 从当前剩余普通件中选出下一个待尝试的物品类型。
     *
     * <p>候选顺序沿用原插入阶段的面积与综合尺寸评分排序；同一类型的不同旋转只代表
     * 同一个物品，因为整体重排时会重新枚举该物品全部允许方向。</p>
     *
     * @param freeBoxes 各工件类型当前尚未使用的数量。
     * @param allowedTypes 普通件类型允许掩码。
     * @param rejectedTypeIndexes 当前 Sp 已完整重排失败、无需再次测试的类型下标集合。
     * @return 下一个普通件候选；没有可测试物品时返回 {@code null}。
     */
    private CandidateItem nextCandidateItem(int[] freeBoxes,
                                            boolean[] allowedTypes,
                                            Set<Integer> rejectedTypeIndexes) {
        GeneralBlock[] candidateBlocks = new BlockGenerator(inst).generateSingleBlock(true, allowedTypes);
        sortInsertionBlocksByPriorityScore(candidateBlocks);
        Set<Integer> inspectedTypeIndexes = new HashSet<>();
        for (GeneralBlock block : candidateBlocks) {
            if (block.component == null || block.component.length != 1 || block.cuboid.isEmpty()) {
                continue;
            }
            int typeIndex = block.component[0];
            if (!inspectedTypeIndexes.add(typeIndex)
                    || rejectedTypeIndexes.contains(typeIndex)
                    || typeIndex < 0
                    || typeIndex >= freeBoxes.length
                    || freeBoxes[typeIndex] <= 0) {
                continue;
            }
            return new CandidateItem(typeIndex, inst.boxes[typeIndex]);
        }
        return null;
    }

    /**
     * 用当前 Sp 占用面积和候选普通件面积执行必要条件筛选。
     *
     * @param board 当前 Sp 布局，包含优先件及此前成功插入的普通件。
     * @param candidate 待尝试的一个普通件。
     * @return 两者矩形面积和不超过板材面积时返回 {@code true}；否则候选必定无法装入。
     */
    private boolean passesCandidateAreaBound(BoardStateSnapshot board, Box candidate) {
        if (board == null || candidate == null) {
            return false;
        }
        double occupiedArea = 0.0;
        for (PlacedCuboid placedCuboid : board.getPlacedCuboids()) {
            occupiedArea += Math.max(0.0, placedCuboid.getVolume());
        }
        double boardArea = Math.max(1.0, (double) inst.length * inst.width);
        return occupiedArea + Math.max(0.0, candidate.volume) <= boardArea + 1e-6;
    }

    /**
     * 将一张 Sp 内全部现有工件与一个普通件重新执行单板 Beam Search 验证。
     *
     * @param board 当前 Sp 快照，包含必须保留的优先件和已插入普通件。
     * @param candidate 待插入的普通件；本方法只尝试一个物理副本。
     * @param candidateDeadlineMillis 本次候选验证允许使用的绝对截止时间，单位为毫秒。
     * @return 全部工件确实装入同一张板材时返回重排后的快照；不能完整装入时返回 {@code null}。
     */
    private BoardStateSnapshot repackBoardWithCandidate(BoardStateSnapshot board,
                                                         Box candidate,
                                                         long candidateDeadlineMillis) {
        if (board == null || candidate == null || System.currentTimeMillis() >= candidateDeadlineMillis) {
            return null;
        }

        List<Box> temporaryBoxes = new ArrayList<>();
        IdentityHashMap<Box, Box> canonicalBoxes = new IdentityHashMap<>();
        for (PlacedCuboid placedCuboid : board.getPlacedCuboids()) {
            if (placedCuboid.box == null) {
                return null;
            }
            Box copiedBox = placedCuboid.box.copy();
            copiedBox.count = 1;
            temporaryBoxes.add(copiedBox);
            canonicalBoxes.put(copiedBox, placedCuboid.box);
        }
        Box copiedCandidate = candidate.copy();
        copiedCandidate.count = 1;
        temporaryBoxes.add(copiedCandidate);
        canonicalBoxes.put(copiedCandidate, candidate);

        Instance candidateInstance = new Instance(inst, new ArrayList<>(temporaryBoxes));
        SpaceManager candidateSpaceManager = new SpaceManager(
                SpaceComparator.getSpaceComparator(candidateInstance, 1));
        BeamSearch candidateSearch = new BeamSearch(candidateSpaceManager, candidateInstance);
        int minContainerCount = (int) Math.max(0L,
                (long) (candidateInstance.totalBoxVolume
                        / Math.max(1L, (long) candidateInstance.length * candidateInstance.width)));
        ExecutionResult candidateResult = candidateSearch.solveUntil(
                candidateDeadlineMillis, minContainerCount);
        if (!isCompleteSingleBoardRepack(candidateResult, temporaryBoxes.size())) {
            return null;
        }

        List<PlacedCuboid> canonicalPlacements = new ArrayList<>(temporaryBoxes.size());
        for (PlacedCuboid placedCuboid : candidateResult.solutions.get(0).getPlacedCuboid()) {
            Box canonicalBox = canonicalBoxes.get(placedCuboid.box);
            if (canonicalBox == null) {
                return null;
            }
            canonicalPlacements.add(new PlacedCuboid(
                    placedCuboid.x,
                    placedCuboid.y,
                    placedCuboid.length,
                    placedCuboid.width,
                    canonicalBox,
                    placedCuboid.ortIdx));
        }
        List<Space> remainingSpaces = SpaceManager.calculateResidualSpaces(
                inst.length, inst.width, canonicalPlacements);
        return new BoardStateSnapshot(canonicalPlacements, remainingSpaces);
    }

    /**
     * 判断候选整体重排是否确实只使用一张板材并放入了全部待验证工件。
     *
     * @param result 单板候选验证的求解结果。
     * @param expectedPieceCount 当前 Sp 工件数加一个候选普通件后的应放置总数。
     * @return 仅一张板、没有未放工件且放置数量匹配时返回 {@code true}。
     */
    private boolean isCompleteSingleBoardRepack(ExecutionResult result, int expectedPieceCount) {
        if (result == null || result.solutions.size() != 1 || result.unplacedCounts == null) {
            return false;
        }
        for (int unplacedCount : result.unplacedCounts) {
            if (unplacedCount > 0) {
                return false;
            }
        }
        return result.solutions.get(0).getPlacedCuboid().size() == expectedPieceCount;
    }

    /**
     * 按剩余普通件数量估算当前和后续 Sp 仍可能执行的候选验证次数。
     *
     * @param freeBoxes 各普通件类型尚未使用的数量。
     * @param allowedTypes 普通件类型允许掩码。
     * @param rejectedTypeIndexes 当前 Sp 已失败、不会再次验证的类型下标。
     * @return 至少为 1 的剩余物理候选件数量估计。
     */
    private long remainingCandidateItemCount(int[] freeBoxes,
                                             boolean[] allowedTypes,
                                             Set<Integer> rejectedTypeIndexes) {
        long count = 0L;
        for (int index = 0; index < freeBoxes.length; index++) {
            boolean allowed = allowedTypes == null
                    || (index < allowedTypes.length && allowedTypes[index]);
            if (allowed && !rejectedTypeIndexes.contains(index)) {
                count += Math.max(0, freeBoxes[index]);
            }
        }
        return Math.max(1L, count);
    }

    /**
     * 为一次候选整体重排分配时间片。
     *
     * @param globalDeadlineMillis 插入阶段的绝对截止时间，单位为毫秒；
     *                             {@link Long#MAX_VALUE} 表示插入阶段不设总时限。
     * @param remainingCandidateCount 当前和后续待尝试的物理普通件数量估计。
     * @param remainingBoardCount 包含当前 Sp 在内的尚待处理 Sp 数量。
     * @return 本次候选验证的绝对截止时间；无总时限时仅受单次验证上限约束。
     */
    private long candidateRepackDeadline(long globalDeadlineMillis,
                                         long remainingCandidateCount,
                                         int remainingBoardCount) {
        long now = System.currentTimeMillis();
        long configuredLimit = PackingRuntimeConfig.candidateRepackTimeLimitMs();
        if (globalDeadlineMillis == Long.MAX_VALUE) {
            return addMillisSaturated(now, configuredLimit);
        }
        long remainingMillis = Math.max(0L, globalDeadlineMillis - now);
        long attemptCount = Math.max(1L, remainingCandidateCount * Math.max(1, remainingBoardCount));
        long fairShareMillis = Math.max(1L, remainingMillis / attemptCount);
        long allocatedMillis = Math.max(1L, Math.min(fairShareMillis, configuredLimit));
        return Math.min(globalDeadlineMillis, addMillisSaturated(now, allocatedMillis));
    }

    /**
     * 计算不发生 long 溢出的未来时间戳。
     *
     * @param currentMillis 当前时间戳，单位为毫秒。
     * @param durationMillis 需要增加的时长，单位为毫秒。
     * @return 饱和到 {@link Long#MAX_VALUE} 的未来时间戳。
     */
    private long addMillisSaturated(long currentMillis, long durationMillis) {
        if (durationMillis <= 0L || currentMillis >= Long.MAX_VALUE - durationMillis) {
            return Long.MAX_VALUE;
        }
        return currentMillis + durationMillis;
    }

    /**
     * 表示一次候选整体重排待尝试的普通件类型。
     *
     * @param typeIndex 候选在混合实例 {@link Instance#boxes} 中的数组下标。
     * @param box 该候选的规范 Box 对象；成功后用于扣减对应剩余数量。
     */
    private record CandidateItem(int typeIndex, Box box) {
    }

    private BoardStateSnapshot createBoardStateSnapshot(Solution solution) {
        ArrayList<Space> residualSpaces = SpaceManager.calculateResidualSpaces(
                inst.length,
                inst.width,
                solution.getPlacedCuboid());
        return new BoardStateSnapshot(solution.getPlacedCuboid(), residualSpaces);
    }

    /**
     * 按论文公式的优先级分数降序排列插入候选。
     *
     * <p>对于一个普通件方向：</p>
     * <pre>
     * S_i = (w_i * h_i) * phi_i
     * phi_i = 1 + w_i^2 / W^2 + h_i^2 / H^2
     * </pre>
     * 这里使用 GeneralBlock 的方向外接矩形面积和当前板材尺寸计算公式。
     * blockVolume 就是 w_i*h_i；boxVolume 仍保留给最终装载面积统计，避免
     * 将排序指标和排样结果统计混用。</p>
     */
    private void sortInsertionBlocksByPriorityScore(GeneralBlock[] blocks) {
        Arrays.sort(blocks, new Comparator<GeneralBlock>() {
            @Override
            public int compare(GeneralBlock left, GeneralBlock right) {
                int result = Double.compare(
                        insertionPriorityScore(right),
                        insertionPriorityScore(left));
                if (result != 0) {
                    return result;
                }

                long leftBoundingArea = (long) left.length * left.width;
                long rightBoundingArea = (long) right.length * right.width;
                return Long.compare(rightBoundingArea, leftBoundingArea);
            }
        });
    }

    /** 计算一个普通件方向的优先级分数，分数越大越优先尝试。 */
    private double insertionPriorityScore(GeneralBlock block) {
        double normalizedLength = (double) block.length / inst.length;
        double normalizedWidth = (double) block.width / inst.width;
        double shapeCoefficient = 1
                + normalizedLength * normalizedLength
                + normalizedWidth * normalizedWidth;
        // 修改原因：公式的基准项明确是当前方向的 w_i*h_i，使用
        // blockVolume 可避免把实际装载面积字段误作为尺寸面积。
        return block.blockVolume * shapeCoefficient;
    }

    /**
     * 返回剩余空隙面积最大的 Sp 在候选列表中的位置。
     * 面积相同的时候保留输入顺序，避免无意义地改变结果顺序。
     */
    private int findLargestResidualSpaceBoard(List<BoardStateSnapshot> boards) {
        int selectedIndex = 0;
        double largestSpaceArea = -1;

        for (int i = 0; i < boards.size(); i++) {
            double currentLargestArea = getLargestResidualSpaceArea(boards.get(i));
            if (currentLargestArea > largestSpaceArea) {
                largestSpaceArea = currentLargestArea;
                selectedIndex = i;
            }
        }
        return selectedIndex;
    }

    /** 计算一张 Sp 中最大的矩形剩余空隙面积。 */
    private double getLargestResidualSpaceArea(BoardStateSnapshot board) {
        double largestArea = 0;
        for (Space space : board.getRemainingSpaces()) {
            if (space.volume > largestArea) {
                largestArea = space.volume;
            }
        }
        return largestArea;
    }

    private int[] initialFreeBoxes(boolean[] allowedTypes) {
        int[] freeBoxes = new int[inst.boxes.length];
        for (int i = 0; i < inst.boxes.length; i++) {
            if (allowedTypes == null || (i < allowedTypes.length && allowedTypes[i])) {
                freeBoxes[i] = inst.boxes[i].count;
            }
        }
        return freeBoxes;
    }

    private void fillUnplacedBoxes(ExecutionResult result,
                                   int[] freeBoxes,
                                   boolean[] allowedTypes) {
        result.unplacedBoxes = new ArrayList<>();
        result.unplacedCounts = freeBoxes.clone();
        result.unplacedBoxesVol = 0;

        for (int i = 0; i < freeBoxes.length; i++) {
            boolean allowed = allowedTypes == null
                    || (i < allowedTypes.length && allowedTypes[i]);
            if (!allowed || freeBoxes[i] <= 0) {
                continue;
            }

            result.unplacedBoxes.add(inst.boxes[i]);
            result.unplacedBoxesVol += inst.boxes[i].volume * freeBoxes[i];
        }
    }

    public ExecutionResult multipSolve(Instance newInst, int containerNum, boolean decNode) {
        return multipSolve(newInst, containerNum, decNode, Long.MAX_VALUE);
    }

    /** 截止时间覆盖内部 Beam 扩展；超时时返回已找到的合法部分布局。 */
    private ExecutionResult multipSolve(Instance newInst, int containerNum, boolean decNode,
                                      long deadlineMillis) {
        ExecutionResult exeResult = new ExecutionResult();

        GeneralBlock[] allBlocks = new BlockGenerator(newInst).generateSingleBlock(false);

        Arrays.sort(allBlocks, (o1, o2) -> {
            double diff = o1.boxVolume - o2.boxVolume;
            if (diff < 0) {
                return 1;
            } else if (diff > 0) {
                return -1;
            } else {
                double d = o1.length - o2.length;
                if (d < 0) {
                    return 1;
                } else if (d > 0) {
                    return -1;
                } else {
                    return 0;
                }
            }
        });

        Node bestNode = new Node();

        int WIDTH = 10;
        if (decNode) {
            WIDTH = Math.max(1, allBlocks.length / 3);
        }
        State state;
        state = State.createMultipleInitState(newInst, spaceManager, allBlocks, containerNum);

        Queue<Node> newNodes = new LinkedList<>();
        Node root = new Node();
        root.state = state;
        bestNode = root;
        newNodes.add(root);
        while (!newNodes.isEmpty() && System.currentTimeMillis() < deadlineMillis) {
            int limit = newNodes.size();
            TreeSet<Node> offspring = new TreeSet<Node>();
            for (int i = 0; i < limit; i++) {
                if (System.currentTimeMillis() >= deadlineMillis) {
                    break;
                }
                Node curNode = newNodes.poll();
                ArrayList<Node> curChild = new ArrayList<Node>();
                int childCount = (curNode.state.countPlacedBlock() == 0 ? WIDTH * WIDTH : WIDTH);
                blockSearch(curNode.state, childCount, curChild, 1, deadlineMillis);
                // 不完整搜索也保留实际已放面积最多的状态，不能使用贪心估分代表实际布局。
                if (curNode.state.getPackedVolume() > bestNode.state.getPackedVolume()) {
                    bestNode = curNode;
                }
                if (curChild.isEmpty()) {
                    if (curNode.state.getPackedVolume() >= bestNode.state.getPackedVolume()) {
                        bestNode = curNode;
                    }
                } else {
                    for (int j = 0; j < curChild.size(); j++) {
                        if (curChild.get(j).state.getPackedVolume() > bestNode.state.getPackedVolume()) {
                            bestNode = curChild.get(j);
                        }
                        update(offspring, WIDTH, curChild.get(j));
                    }
                }
            }
            for (Node node : offspring) {
                newNodes.add(node);
            }

        }
        for (int i = 0; i < containerNum; i++) {

                exeResult.solutions.add(bestNode.state.toSolution(i));

        }


        exeResult.unplacedBoxes = new ArrayList<Box>();
        exeResult.unplacedBoxesVol = 0;

        exeResult.unplacedCounts = bestNode.state.getFreeBoxes().clone();
        for (int i = 0; i < bestNode.state.getFreeBoxes().length; i++) {
            for (int remaining = 0; remaining < exeResult.unplacedCounts[i]; remaining++) {
                exeResult.unplacedBoxes.add(newInst.boxes[i]);
                exeResult.unplacedBoxesVol += newInst.boxes[i].volume;
            }
        }

        return exeResult;
    }

    /**
     * 通过反复移出低利用率板材并尝试重排，优化跨板材的整体装载结果。
     *
     * <p>修改原因：原实现一旦成功减少一张板材，就直接退出整个方法；
     * 同时一次候选遍历没有改进时也容易被误认为全局优化已经结束。
     * 现在只结束当前板材的局部尝试，成功减板后会基于最新结果继续寻找
     * 下一张可删除的板材，直到达到下界、时间上限或连续多轮完整扫描都没有减板。
     * 初始至少十板时连续两轮未减板会加入三板候选，减板后恢复一两板候选；
     * 部分搬运只有在残余可合法放回一张移出板后才提交，且不重置无减板轮数。</p>
     */
    public ExecutionResult ImproveByRepack(ExecutionResult executionResult, double maxTime, Random random) {
        return ImproveByRepack(executionResult, maxTime, random, 0);
    }

    /** 0 禁用下界停止；正数对应纯矩形整案例或混排当前阶段的矩形集合下界。 */
    public ExecutionResult ImproveByRepack(ExecutionResult executionResult, double maxTime, Random random,
                                           int lowerBound) {
        if (lowerBound < 0) {
            throw new IllegalArgumentException("Lower bound must be nonnegative.");
        }
        System.out.println("Start improving solution by repacking.");
        long startTime = System.currentTimeMillis();
        int containerNum = executionResult.solutions.size();
        RepackStatistics statistics = new RepackStatistics();
        statistics.executed = true;
        statistics.requestedTimeLimitMs = Math.max(0L, Math.round(maxTime * 1000.0));
        statistics.boardCountBefore = containerNum;
        long deadlineMillis = statistics.requestedTimeLimitMs >= Long.MAX_VALUE - startTime
                ? Long.MAX_VALUE : startTime + statistics.requestedTimeLimitMs;
        boolean expansionAllowed = containerNum >= MIN_INITIAL_BOARDS_FOR_EXPANSION;
        int receiverLimit = BASE_RECEIVER_LIMIT;
        statistics.maxReceiverCount = receiverLimit;
        lastRepackStatistics = statistics;
        if (lowerBound > 0 && containerNum < lowerBound) {
            throw new IllegalStateException("Packing lower bound exceeds the current board count.");
        }
        if (lowerBound > 0 && containerNum == lowerBound) {
            statistics.stopReason = "lower_bound_reached_before_optimization";
            statistics.boardCountAfter = containerNum;
            return executionResult;
        }
        List<Integer> locations = new ArrayList<>();

        int location;
        // 修改原因：flag 只记录了若干次“没有可选板材”，不能明确表示
        // 是否已经完整扫描过所有低利用率板材。这里改为记录完整无减板轮数。
        int noImprovementSweeps = 0;

        while (true) {
            statistics.outerLoopIterations++;
            // 时间上限仍然是全局优化的第一停止条件。
            if ((System.currentTimeMillis() - startTime) * 0.001 >= maxTime) {
                statistics.timeLimitReached = true;
                statistics.stopReason = "time_limit_reached";
                break;
            }

            // 双重停止条件：连续完成多轮完整扫描且没有减少板材数量时，
            // 即使尚未耗尽时间，也停止对当前结果继续进行无效重排。
            if (noImprovementSweeps >= MAX_NO_IMPROVEMENT_SWEEPS) {
                statistics.stopReason = "no_improvement_sweeps";
                break;
            }

            // 修改原因：减板后 solutions 数量会变化，不能继续使用初始板材数。
            // 每轮重新读取数量，保证后续索引和候选集合与当前结果一致。
            containerNum = executionResult.solutions.size();
            if (containerNum <= 1) {
                // 只剩一张板材时没有继续减板的可能。
                statistics.stopReason = "single_board_remaining";
                break;
            }

            ArrayList<Box> unplacedBox = new ArrayList<>();
            double unplacedBoxVol = 0;

            double allAvgUtilization = executionResult.avgUtilization;
            if (containerNum <= 200) {
                allAvgUtilization = 100;
            } else {
                if (allAvgUtilization < 90) {
                    allAvgUtilization = 90;
                }
            }
            double maxUtilization = 100;
            boolean decNode = false;
            if (containerNum >= 350 && allAvgUtilization < 94 && executionResult.solution.getInst().boxes[0].containerOrientId != 0) {
                maxUtilization = 97;
                decNode = true;
            }

            System.out.println("Selecting boards with low utilization for repacking...");
            location = -1;
            double u = Double.MAX_VALUE;

            for (int i = 0; i < containerNum; i++) {
                if (locations.contains(i)) {
                    continue;
                }
                if (executionResult.solutions.get(i).getUtilization() < u) {
                    location = i;
                    u = executionResult.solutions.get(location).getUtilization();
                }
            }

            if (location == -1 || executionResult.solutions.get(location).getUtilization() > allAvgUtilization) {
                // 修改原因：只有在所有候选板材都被尝试后才结束一轮，不能
                // 因为某一次局部尝试无效就直接停止整个优化。
                locations.clear();
                noImprovementSweeps++;
                statistics.noImprovementSweeps = noImprovementSweeps;
                System.out.println("Completed repack sweep without reducing board count. "
                        + "No-reduction sweeps: " + noImprovementSweeps
                        + "/" + MAX_NO_IMPROVEMENT_SWEEPS);
                if (expansionAllowed && receiverLimit == BASE_RECEIVER_LIMIT
                        && noImprovementSweeps >= NO_REDUCTION_SWEEPS_BEFORE_EXPANSION
                        && noImprovementSweeps < MAX_NO_IMPROVEMENT_SWEEPS) {
                    receiverLimit = EXPANDED_RECEIVER_LIMIT;
                    statistics.neighborhoodExpansions++;
                    statistics.maxReceiverCount = receiverLimit;
                    // 标准输出由现有运行器写入 optimized.log，无需另建日志文件。
                    System.out.println("[Repack neighborhood expanded] receivers: 2 -> 3; initial boards: "
                            + statistics.boardCountBefore + "; no-reduction sweeps: " + noImprovementSweeps);
                }
                if (noImprovementSweeps >= MAX_NO_IMPROVEMENT_SWEEPS) {
                    // 这是无改进保护，不替代前面的时间限制。
                    statistics.stopReason = "no_improvement_sweeps";
                    break;
                }
                continue;
            }

            // 只有确认当前板材满足低利用率条件后才记录，避免把 -1
            // 或不符合条件的索引混入本轮已尝试集合。
            locations.add(location);
            statistics.candidateBoardAttempts++;

            for (int i = 0; i < executionResult.solutions.get(location).getPlacedCuboid().size(); i++) {
                unplacedBox.add(executionResult.solutions.get(location).getPlacedCuboid().get(i).box.copy());
            }
            for (int i = 0; i < unplacedBox.size(); i++) {
                unplacedBoxVol += unplacedBox.get(i).volume;
            }

            ArrayList<Solution> newSolutions = new ArrayList<Solution>();
            for (int i = 0; i < containerNum; i++) {
                if (i != location) {
                    newSolutions.add(executionResult.solutions.get(i));
                }
            }

            long pairGenerationStartNanos = System.nanoTime();
            ArrayList<Integer> pairSet = new ArrayList<>();
            for (int i = 0; i < newSolutions.size(); i++) {
                if (newSolutions.get(i).getUtilization() > maxUtilization) {
                    continue;
                }

                // 增加 a == b 的候选，使被移出的板材可以尝试与某一张
                // 现有板材合并到一张板上。原实现只尝试两张现有板材，
                // 当总板材数较少时无法执行真正的减板优化。
                pairSet.add(i * containerNum + i);

                for (int j = i + 1; j < newSolutions.size(); j++) {
                    if ((newSolutions.get(i).getUtilization() > allAvgUtilization && newSolutions.get(j).getUtilization() > allAvgUtilization)
                            || newSolutions.get(j).getUtilization() > maxUtilization) {
                        continue;
                    }
                    pairSet.add(i * containerNum + j);
                }

            }
            statistics.pairCandidatesGenerated += pairSet.size();
            statistics.pairGenerationTimeMs += elapsedMillis(pairGenerationStartNanos);

            // 修改原因：当前板材已经计入 locations，候选为空时只应跳过
            // 当前板材，继续扫描本轮其他低利用率板材。
            if (pairSet.isEmpty()) {
                continue;
            }

            Collections.shuffle(pairSet, random);
            int iteration = 0;
            ArrayList<int[]> candidates = new ArrayList<>();
            for (int pair : pairSet) {
                int a = pair / containerNum;
                int b = pair % containerNum;
                candidates.add(a == b ? new int[]{a} : new int[]{a, b});
            }
            if (receiverLimit == EXPANDED_RECEIVER_LIMIT) {
                ArrayList<int[]> triples = tripleCandidates(newSolutions, maxUtilization, random);
                statistics.tripleCandidatesGenerated += triples.size();
                candidates.addAll(triples);
                Collections.shuffle(candidates, random);
            }
            int index = 0;
            // 记录当前 pairSet 遍历是否产生过改进；它只控制是否重新
            // 从第一个候选开始，不再承担全局优化的停止职责。
            boolean improvedInCurrentPass = false;

            // 修改原因：删除原来的 maxIter=1000 硬截止。当前候选有限，
            // 无改进时会在完整遍历后退出；有改进时重新遍历，最终由时间
            // 上限或完整无改进遍历控制，避免在 1000 次时提前截断。
            while (unplacedBox.size() > 0) {
                if ((System.currentTimeMillis() - startTime) * 0.001 >= maxTime) {
                    break;
                }

                iteration++;
                int[] receivers = candidates.get(index);
                boolean triple = receivers.length == EXPANDED_RECEIVER_LIMIT;
                if (triple) {
                    statistics.tripleRepackAttempts++;
                } else {
                    statistics.pairRepackAttempts++;
                }
                // Instance 会改写 typeNum/count，因此残余物品也必须复制，不能污染已提交布局。
                ArrayList<Box> boxs = copyBoxes(unplacedBox);
                for (int receiver : receivers) {
                    for (PlacedCuboid piece : newSolutions.get(receiver).getPlacedCuboid()) {
                        boxs.add(piece.box.copy());
                    }
                }

                Instance newInst = new Instance(inst, boxs);
                long pairRepackStartNanos = System.nanoTime();
                long attemptDeadline = triple
                        ? Math.min(deadlineMillis, System.currentTimeMillis() + TRIPLE_REPACK_TIME_LIMIT_MS)
                        : deadlineMillis;
                ExecutionResult newSol = solveRepackCandidate(newInst, receivers.length, decNode, attemptDeadline);
                long pairRepackElapsedMs = elapsedMillis(pairRepackStartNanos);
                if (triple) {
                    statistics.tripleRepackTimeMs += pairRepackElapsedMs;
                } else {
                    statistics.pairRepackTimeMs += pairRepackElapsedMs;
                    statistics.longestPairRepackTimeMs = Math.max(
                            statistics.longestPairRepackTimeMs, pairRepackElapsedMs);
                }

                // 数量守恒、方向、边界及互不重叠全部通过后，才考虑面积进展。
                if (validLocalResult(newInst, newSol, receivers.length)
                        && newSol.unplacedBoxesVol < unplacedBoxVol) {
                    Solution donor = null;
                    boolean complete = newSol.unplacedBoxes.isEmpty();
                    if (!complete) {
                        long rebuildStartNanos = System.nanoTime();
                        statistics.sameBoardRebuildAttempts++;
                        Instance donorInst = new Instance(inst, copyBoxes(newSol.unplacedBoxes));
                        ExecutionResult rebuilt = rebuildDonor(donorInst, deadlineMillis);
                        if (validLocalResult(donorInst, rebuilt, 1) && rebuilt.unplacedBoxes.isEmpty()) {
                            donor = rebuilt.solutions.get(0);
                        }
                        statistics.sameBoardRebuildTimeMs += elapsedMillis(rebuildStartNanos);
                    }
                    // 事务边界：剩余物品无法合法回到一张移出板时，接收板也不能更新。
                    if (!complete && donor == null) {
                        statistics.rejectedPartialTransfers++;
                    } else {
                        if (!triple) {
                            statistics.successfulPairRepackAttempts++;
                            statistics.successfulPairRepackTimeMs += pairRepackElapsedMs;
                        }
                        System.out.println("iter" + iteration +
                                "\t\t Improve solution by repack " + unplacedBoxVol / inst.length / inst.width
                                + "->" + newSol.unplacedBoxesVol / inst.length / inst.width + " , unplacedBoxesSize:"
                                + newSol.unplacedBoxes.size());
                        System.out.println("currentRepackCostTime:" + ((System.currentTimeMillis() - startTime) * 0.001)
                                + "s , currentRepackMaxTime:" + maxTime + "s");
                        unplacedBox = newSol.unplacedBoxes;
                        unplacedBoxVol = newSol.unplacedBoxesVol;
                        for (int i = 0; i < receivers.length; i++) {
                            newSolutions.set(receivers[i], newSol.solutions.get(i));
                        }
                        if (!complete) {
                            ArrayList<Solution> committed = new ArrayList<>(newSolutions);
                            committed.add(location, donor);
                            executionResult.solutions = committed;
                            executionResult.setAvgUtilization();
                            statistics.committedPartialTransfers++;
                        } else if (triple) {
                            statistics.tripleBoardReductions++;
                        }
                        improvedInCurrentPass = true;
                    }
                }

                if (index < candidates.size() - 1) {
                    index++;
                } else {
                    if (improvedInCurrentPass) {
                        // 当前轮有改进，重新完整扫描 pairSet，继续寻找
                        // 是否还能把剩余工件放入现有板材。
                        index = 0;
                        improvedInCurrentPass = false;
                    } else {
                        // 这里只结束当前被移出板材的候选遍历；外层仍会
                        // 继续选择本轮其他低利用率板材。
                        break;
                    }
                }
            }

            if (unplacedBox.size() == 0) {
                System.out.println("Improved success. Continue searching for another removable board.");
                executionResult.solutions = newSolutions;
                statistics.boardReductions++;

                // 修改原因：减板后平均利用率和板材数量都已变化，立即
                // 更新状态并清空旧索引，下一轮必须基于新解重新选择板材。
                executionResult.setAvgUtilization();
                containerNum = executionResult.solutions.size();
                locations.clear();
                noImprovementSweeps = 0;
                if (receiverLimit == EXPANDED_RECEIVER_LIMIT) {
                    receiverLimit = BASE_RECEIVER_LIMIT;
                    statistics.neighborhoodResets++;
                    System.out.println("[Repack neighborhood restored] receivers: 3 -> 2 after board reduction; boards: "
                            + containerNum);
                }
                // 仅在完整新解已提交、实际板数已减少后判断，不把局部未排面积下降当成减板。
                if (lowerBound > 0 && containerNum == lowerBound) {
                    statistics.stopReason = "lower_bound_reached_after_reduction";
                    System.out.println("Packing lower bound reached after reduction: " + lowerBound + " boards.");
                    break;
                }
                continue;
            }

            // 部分搬运已逐次验证并提交；不清空 locations/无减板轮数，防止小幅搬运掩盖停滞。
        }
        if ("not_started".equals(statistics.stopReason)) {
            statistics.stopReason = "completed";
        }
        statistics.noImprovementSweeps = noImprovementSweeps;
        statistics.boardCountAfter = executionResult.solutions.size();
        statistics.elapsedTimeMs = Math.max(0L, System.currentTimeMillis() - startTime);
        return executionResult;
    }

    /** 默认仍使用原 Beam 求解；独立入口便于验证事务提交和邻域切换。 */
    ExecutionResult solveRepackCandidate(Instance instance, int receivers, boolean decNode, long deadlineMillis) {
        return multipSolve(instance, receivers, decNode, deadlineMillis);
    }

    /** 只重建一张移出板，不允许退化为多板；失败或超时直接放弃这次部分搬运。 */
    ExecutionResult rebuildDonor(Instance instance, long deadlineMillis) {
        GeneralBlock[] blocks = new BlockGenerator(instance).generateSingleBlock(true);
        State state = State.createMultipleInitState(instance, spaceManager, blocks, 1);
        while (state.hasFreeSpace() && System.currentTimeMillis() < deadlineMillis) {
            Space space = state.chooseBestSpace();
            GeneralBlock block = state.chooseBestBlock(space);
            state = block == null ? state.deleteSpace(space) : state.packBlock(space, block);
        }
        ExecutionResult result = new ExecutionResult();
        result.solutions.add(state.toSolution(0));
        result.unplacedBoxes = new ArrayList<>();
        for (int i = 0; i < instance.boxes.length; i++) {
            for (int j = 0; j < state.getFreeBoxes()[i]; j++) {
                result.unplacedBoxes.add(instance.boxes[i]);
            }
        }
        return result;
    }

    private static ArrayList<Box> copyBoxes(List<Box> boxes) {
        ArrayList<Box> copies = new ArrayList<>();
        for (Box box : boxes) {
            copies.add(box.copy());
        }
        return copies;
    }

    /** 一半候选来自低利用率板，一半随机抽样，兼顾空余面积和不同空间形状。 */
    private static ArrayList<int[]> tripleCandidates(List<Solution> boards, double maxUtilization, Random random) {
        ArrayList<Integer> eligible = new ArrayList<>();
        for (int i = 0; i < boards.size(); i++) {
            if (boards.get(i).getUtilization() <= maxUtilization) {
                eligible.add(i);
            }
        }
        eligible.sort(Comparator.comparingDouble(i -> boards.get(i).getUtilization()));
        int lowCount = Math.min(TRIPLE_RECEIVER_POOL_SIZE / 2, eligible.size());
        ArrayList<Integer> pool = new ArrayList<>(eligible.subList(0, lowCount));
        ArrayList<Integer> others = new ArrayList<>(eligible.subList(lowCount, eligible.size()));
        Collections.shuffle(others, random);
        pool.addAll(others.subList(0, Math.min(TRIPLE_RECEIVER_POOL_SIZE - pool.size(), others.size())));
        ArrayList<int[]> triples = new ArrayList<>();
        // 不使用总面积必须装入三板的过滤：未排面积下降且残余能回到移出板也有效。
        for (int i = 0; i < pool.size(); i++) {
            for (int j = i + 1; j < pool.size(); j++) {
                for (int k = j + 1; k < pool.size(); k++) {
                    triples.add(new int[]{pool.get(i), pool.get(j), pool.get(k)});
                }
            }
        }
        Collections.shuffle(triples, random);
        return new ArrayList<>(triples.subList(0, Math.min(MAX_TRIPLE_CANDIDATES, triples.size())));
    }

    /** 局部物理物品逐一守恒，避免只比较面积而遗漏、重复工件或接纳非法布局。 */
    private static boolean validLocalResult(Instance instance, ExecutionResult result, int boardCount) {
        if (result == null || result.solutions.size() != boardCount || result.unplacedBoxes == null) {
            return false;
        }
        IdentityHashMap<Box, Integer> remaining = new IdentityHashMap<>();
        for (Box box : instance.boxes) {
            remaining.put(box, box.count);
        }
        for (Solution board : result.solutions) {
            if (board == null) {
                return false;
            }
            List<PlacedCuboid> pieces = board.getPlacedCuboid();
            for (int i = 0; i < pieces.size(); i++) {
                PlacedCuboid piece = pieces.get(i);
                if (piece == null || !consumeBox(remaining, piece.box)) {
                    return false;
                }
                boolean orientation = false;
                for (int[] variation : piece.box.variation) {
                    orientation |= piece.length == variation[1] && piece.width == variation[0];
                }
                if (!orientation || piece.length <= 0 || piece.width <= 0 || piece.x < 0 || piece.y < 0
                        || (long) piece.x + piece.length > instance.length
                        || (long) piece.y + piece.width > instance.width) {
                    return false;
                }
                for (int j = 0; j < i; j++) {
                    PlacedCuboid other = pieces.get(j);
                    if (piece.x < (long) other.x + other.length && other.x < (long) piece.x + piece.length
                            && piece.y < (long) other.y + other.width && other.y < (long) piece.y + piece.width) {
                        return false;
                    }
                }
            }
        }
        double unplacedArea = 0;
        for (Box box : result.unplacedBoxes) {
            if (!consumeBox(remaining, box)) {
                return false;
            }
            unplacedArea += box.volume;
        }
        if (remaining.values().stream().anyMatch(count -> count != 0)) {
            return false;
        }
        result.unplacedBoxesVol = unplacedArea;
        return true;
    }

    private static boolean consumeBox(IdentityHashMap<Box, Integer> counts, Box box) {
        Integer count = counts.get(box);
        if (count == null || count <= 0) {
            return false;
        }
        counts.put(box, count - 1);
        return true;
    }

    /** 统一将纳秒级监控时间转换为非负毫秒，不参与任何排样决策。 */
    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    public void update(TreeSet<Node> offspring, int WIDTH, Node v) {
        if (offspring.contains(v)) {
            Node s = offspring.floor(v);
            if (s.state.getSpaceManager().getTotalSpaceArea() < v.state.getSpaceManager().getTotalSpaceArea()) {
                offspring.remove(s);
                offspring.add(v);
            }
        } else {
            offspring.add(v);
            if (offspring.size() > WIDTH) {
                offspring.remove(offspring.last());
            }
        }
    }

    public class Node implements Comparable<Node> {
        State state = null;
        Double score = -Double.MAX_VALUE;
        private long hash = -1;

        private void buildHash() {
            long result = Arrays.hashCode(state.getFreeBoxes());

            // 仅使用 freeBoxes 会把“同样剩余工件、不同剩余空间”的状态
            // 合并。新增阶段依赖空间形状，因此把空间坐标纳入状态签名。
            ArrayList<Space> spaces = new ArrayList<>(state.getSpaceManager().getSpaceList());
            spaces.sort((left, right) -> {
                int compare = Integer.compare(left.x1, right.x1);
                if (compare != 0) {
                    return compare;
                }
                compare = Integer.compare(left.y1, right.y1);
                if (compare != 0) {
                    return compare;
                }
                compare = Integer.compare(left.x2, right.x2);
                if (compare != 0) {
                    return compare;
                }
                return Integer.compare(left.y2, right.y2);
            });

            for (Space space : spaces) {
                result = 31 * result + space.x1;
                result = 31 * result + space.y1;
                result = 31 * result + space.x2;
                result = 31 * result + space.y2;
            }
            hash = result;
        }

        @Override
        public int compareTo(Node o) {
            double dv = this.score - o.score;
            if (dv > 0) {
                return -1;
            } else if (dv < 0) {
                return 1;
            }
            this.buildHash();
            o.buildHash();
            if (this.hash < o.hash) {
                return -1;
            } else if (this.hash > o.hash) {
                return 1;
            } else {
                return 0;
            }
        }
    }

    /**
     * 按固定启发式扩展一个 Beam Search 状态。
     *
     * @param state 当前待扩展状态。
     * @param width 当前节点允许生成的最大子节点数量。
     * @param child 下一层候选节点收集器。
     * @param volumeType 完整度评估所使用的体积类型。
     */
    private void blockSearch(State state, int width, ArrayList<Node> child, int volumeType) {
        blockSearch(state, width, child, volumeType, Long.MAX_VALUE);
    }

    private void blockSearch(State state, int width, ArrayList<Node> child, int volumeType, long deadlineMillis) {
        if (System.currentTimeMillis() >= deadlineMillis) {
            return;
        }
        if (state.hasFreeSpace()) {
            Space space = state.chooseBestSpace();
            List<GeneralBlock> candidate = state.chooseBestBlocks(space, width);

            if (candidate.size() == 0) {
                State newState = state.deleteSpace(space);
                blockSearch(newState, width, child, volumeType, deadlineMillis);
            } else {
                for (GeneralBlock pb : candidate) {
                    if (System.currentTimeMillis() >= deadlineMillis) {
                        break;
                    }
                    State newState = state.packBlock(space, pb);
                    Node newNode = new Node();
                    newNode.state = newState;
                    newNode.score = completeSolution(newNode.state, volumeType, deadlineMillis);
                    child.add(newNode);
                }
            }
        }
    }

    private double completeSolution(State initState, int volumeType) {
        return completeSolution(initState, volumeType, Long.MAX_VALUE);
    }

    private double completeSolution(State initState, int volumeType, long deadlineMillis) {
        double score = 0;
        State state = initState;
        while (state.hasFreeSpace() && System.currentTimeMillis() < deadlineMillis) {
            Space space = state.chooseBestSpace();
            GeneralBlock block = state.chooseBestBlock(space);
            if (block == null) {
                state = state.deleteSpace(space);
            } else {
                state = state.packBlock(space, block);
            }
        }
        if (volumeType == 0) {
            score = state.getScoreVolume();
        } else if (volumeType == 1) {
            score = state.getPackedVolume();
        }

        return score;
    }

}
