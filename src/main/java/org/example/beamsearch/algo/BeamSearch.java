package org.example.beamsearch.algo;

import org.example.beamsearch.blockgenerator.BlockGenerator;
import org.example.beamsearch.application.PackingRuntimeConfig;
import org.example.beamsearch.blockgenerator.GeneralBlock;
import org.example.beamsearch.common.*;
import org.example.beamsearch.spacemanager.SpaceManager;
import org.example.beamsearch.state.State;
import org.example.qlearning.packing.FillInsertionMode;
import org.example.qlearning.packing.QFillInsertionPolicy;
import org.example.qlearning.packing.QPackingPolicy;

import java.util.*;

public class BeamSearch {
    private final SpaceManager spaceManager;
    final Instance inst;
    /** 可选的 Q-learning 候选动作策略；为空时严格使用原有启发式扩展。 */
    private final QPackingPolicy qPackingPolicy;
    /** 普通件填入 Sp 时使用的分层 Q-learning 策略；仅插入阶段会传入该对象。 */
    private final QFillInsertionPolicy qFillInsertionPolicy;
    /** 当前插入阶段普通件在极大空闲空间中的坐标锚点。 */
    private PlacementAnchor activeInsertionAnchor = PlacementAnchor.NEAREST_BOARD_CORNER;

    /**
     * 连续完成多少轮“全部低利用率板材均未减少板材数量”后停止全局重排。
     * 该值只作为无改进保护，真正的主停止条件仍然是 maxTime。
     */
    private static final int MAX_NO_IMPROVEMENT_SWEEPS = 3;

    /**
     * 优先件板材插入普通件时的初始集束宽度。
     * 后续搜索阶段会在此基础上按 2 倍逐步扩大，而不是固定使用该值。
     */
    private static final int ORDINARY_INSERTION_INITIAL_BEAM_WIDTH = 4;

    private long finishTime = 0;

    /**
     * 创建使用原有固定启发式候选排序的 Beam Search 求解器。
     *
     * @param spaceManager 初始板材空闲空间管理器。
     * @param inst 当前待排样实例。
     */
    public BeamSearch(SpaceManager spaceManager, Instance inst) {
        this(spaceManager, inst, null, null);
    }

    /**
     * 创建一个矩形排样 Beam Search 求解器。
     *
     * @param spaceManager 初始板材空闲空间管理器。
     * @param inst 当前待排样实例。
     * @param qPackingPolicy Q-learning 候选动作策略；传入 {@code null} 时使用原有启发式策略。
     */
    public BeamSearch(SpaceManager spaceManager, Instance inst, QPackingPolicy qPackingPolicy) {
        this(spaceManager, inst, qPackingPolicy, null);
    }

    /**
     * 创建可同时支持通用 Beam 候选排序和 Sp 填充分层策略的求解器。
     *
     * @param spaceManager 初始板材空闲空间管理器。
     * @param inst 当前待排样实例。
     * @param qPackingPolicy 优先件或普通件新板阶段使用的通用 Q 排序策略。
     * @param qFillInsertionPolicy 仅普通件填入既有 Sp 时使用的分层 Q 策略。
     */
    public BeamSearch(SpaceManager spaceManager,
                      Instance inst,
                      QPackingPolicy qPackingPolicy,
                      QFillInsertionPolicy qFillInsertionPolicy) {
        this.inst = inst;
        this.spaceManager = spaceManager;
        this.qPackingPolicy = qPackingPolicy;
        this.qFillInsertionPolicy = qFillInsertionPolicy;
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
            endState = searchOneBoard(initialState, boardTime, minCon, 0);

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
     * 在已经存在的板材剩余空间中继续排样。
     *
     * <p>该方法专门服务于优先先排的流程。seedBoards 中的矩形会被转成
     * 固定的初始放置块；allowedTypes 决定后续候选，只允许普通件类型进入
     * 搜索。每张优先件板材依次尝试插入普通件，普通件数量在板材之间共享。</p>
     *
     * <p>这是一个阶段性实现：优先件布局本身只保留 BeamSearch 找到的一个
     * 结果。后续若需要比较多个优先件布局，可以让调用方传入多组 seedBoards
     * 并在外层选择最终结果。</p>
     *
     * @param seedBoards 优先件阶段产生的板材快照
     * @param allowedTypes 与 inst.boxes 对应的可继续排样类型
     * @param timeLimit 本次插入允许使用的相对时间，单位为毫秒
     */
    public ExecutionResult packIntoExistingBoards(List<BoardStateSnapshot> seedBoards,
                                                   boolean[] allowedTypes,
                                                   int timeLimit) {
        long deadlineMillis = System.currentTimeMillis() + Math.max(1, timeLimit);
        return packIntoExistingBoardsUntil(seedBoards, allowedTypes, deadlineMillis);
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

        long insertionStartMillis = System.currentTimeMillis();
        QFillInsertionPolicy.ModeDecision modeDecision = null;
        List<BoardStateSnapshot> effectiveSeedBoards = new ArrayList<>(seedBoards);
        // Q-learning 关闭时，候选普通件整体重排是 Sp 插入阶段的固定基线。
        // Q-learning 开启后，该模式与其余三种既有模式共同由 FILL_MODE 动作选择。
        FillInsertionMode insertionMode = FillInsertionMode.CANDIDATE_ITEM_REPACK;
        if (qFillInsertionPolicy != null) {
            modeDecision = qFillInsertionPolicy.selectMode(effectiveSeedBoards);
            insertionMode = modeDecision.mode();
            activeInsertionAnchor = qFillInsertionPolicy.placementAnchorFor(insertionMode);
            if (insertionMode == FillInsertionMode.REPACK_LOWEST_UTILIZATION_BOARD) {
                effectiveSeedBoards = repackLowestUtilizationPriorityBoard(effectiveSeedBoards, deadlineMillis);
            }
        }

        if (insertionMode == FillInsertionMode.CANDIDATE_ITEM_REPACK) {
            ExecutionResult candidateRepackResult = packByCandidateItemRepacking(
                    effectiveSeedBoards, allowedTypes, deadlineMillis);
            if (qFillInsertionPolicy != null) {
                long elapsedMillis = Math.max(0L, System.currentTimeMillis() - insertionStartMillis);
                long allottedMillis = insertionAllottedMillis(deadlineMillis, insertionStartMillis);
                qFillInsertionPolicy.observeMode(
                        modeDecision, candidateRepackResult.boardStates, elapsedMillis, allottedMillis);
            }
            return candidateRepackResult;
        }

        GeneralBlock[] availableBlocks = new BlockGenerator(inst)
                .generateSingleBlock(true, allowedTypes);
        // 修改原因：原候选顺序按 scoreVolume 排列，且此前仅按实际面积排序，
        // 都不能体现“大件优先且兼顾其相对板材尺寸”的选择要求。插入阶段
        // 改用公式 S_i=(w_i*h_i)*(1+w_i^2/W^2+h_i^2/H^2) 降序排列。
        sortInsertionBlocksByPriorityScore(availableBlocks);

        // 修改原因：原实现按 seedBoards 的输入顺序处理 Sp，无法优先使用
        // 剩余空间更大的板材。这里每次从未处理板材中选取最大空隙所在的板材。
        List<BoardStateSnapshot> remainingSeedBoards = new ArrayList<>(effectiveSeedBoards);
        while (!remainingSeedBoards.isEmpty()) {
            int selectedBoardIndex = findLargestResidualSpaceBoard(remainingSeedBoards);
            BoardStateSnapshot seedBoard = remainingSeedBoards.remove(selectedBoardIndex);

            // 普通件已经全部放完时，后面的优先件板材仍然要保留在结果中。
            // 如果全局时间已经耗尽，也必须原样保留当前和后续优先件板材，
            // 不能用“至少 1 毫秒”的旧逻辑继续突破总预算。
            if (availableBlocks.length == 0
                    || hasNoAllowedBoxes(freeBoxes, allowedTypes)
                    || System.currentTimeMillis() >= deadlineMillis) {
                Solution unchangedSolution = seedBoard.toSolution(inst);
                result.solutions.add(unchangedSolution);
                result.boardStates.add(createBoardStateSnapshot(unchangedSolution));
                continue;
            }

            SpaceManager residualSpaceManager = createResidualSpaceManager(seedBoard);
            PlacedBlock[] fixedPriorityBlocks = createPlacedBlocks(seedBoard);
            State initialState = State.createSeededState(
                    inst,
                    residualSpaceManager,
                    freeBoxes,
                    availableBlocks,
                    fixedPriorityBlocks);

            int remainingBoards = remainingSeedBoards.size() + 1;
            long remainingTime = deadlineMillis - System.currentTimeMillis();
            // 插入阶段允许使用 Long.MAX_VALUE 表示不设总时限。必须先截断到 int
            // 可表达范围，避免直接强制转换后溢出为负数，使单板搜索立即失效。
            int boardTime = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(1L, remainingTime / Math.max(1, remainingBoards)));

            // 插入阶段使用动态宽度的集束搜索。初始宽度为 4，根节点
            // 生成 4*4 个候选，之后每层保留 4 个状态；完成当前宽度
            // 搜索后再扩大为 8、16……，以兼顾搜索质量和求解时间。
            State endState = searchInsertionBoard(
                    initialState,
                    boardTime,
                    ORDINARY_INSERTION_INITIAL_BEAM_WIDTH);
            Solution solution = endState.toSolution();
            result.solutions.add(solution);
            result.boardStates.add(createBoardStateSnapshot(solution));

            freeBoxes = endState.getFreeBoxes().clone();
            availableBlocks = BlockGenerator.retainFeasibleBlocks(freeBoxes, availableBlocks);
        }

        fillUnplacedBoxes(result, freeBoxes, allowedTypes);
        result.setAvgUtilization();
        if (qFillInsertionPolicy != null) {
            long elapsedMillis = Math.max(0L, System.currentTimeMillis() - insertionStartMillis);
            long allottedMillis = insertionAllottedMillis(deadlineMillis, insertionStartMillis);
            qFillInsertionPolicy.observeMode(modeDecision, result.boardStates, elapsedMillis, allottedMillis);
        }
        return result;
    }

    /**
     * 对一个初始 State 执行单张板材的集束搜索。
     */
    private State searchOneBoard(State initialState,
                                 int timeLimit,
                                 int minCon,
                                 int volumeType) {
        Node bestNode = new Node();
        long startTime = System.currentTimeMillis();
        finishTime = startTime + Math.max(1, timeLimit);

        int availableBlockCount = initialState.availableBlocks.length;
        for (int width = 4;
             width <= PackingRuntimeConfig.maxBeamWidth() && System.currentTimeMillis() < finishTime;
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
                    || width >= PackingRuntimeConfig.maxBeamWidth()
                    || width > Integer.MAX_VALUE / 2) {
                break;
            }
        }

        // 没有可扩展候选时，root 仍然是一个有效状态。
        return bestNode.state == null ? initialState : bestNode.state;
    }

    /**
     * 使用动态宽度的集束搜索向一张已有优先件板材中插入普通件。
     *
     * <p>该方法与普通新板求解分开，避免改变原有求解入口的搜索宽度。
     * 对每个 beamWidth 阶段，根节点最多生成 beamWidth*beamWidth 个
     * 候选，每层最终保留 beamWidth 个状态；阶段完成后将宽度扩大为
     * 原来的两倍。候选状态使用普通件实际装载面积作为最终比较依据。</p>
     *
     * @param initialBeamWidth 动态搜索的初始宽度
     */
    private State searchInsertionBoard(State initialState,
                                       int timeLimit,
                                       int initialBeamWidth) {
        long startTime = System.currentTimeMillis();
        finishTime = startTime + Math.max(1, timeLimit);

        if (initialBeamWidth <= 0) {
            // 防止错误参数造成无效的宽度循环，保持初始状态可用。
            return initialState;
        }

        // availableBlocks 表示当前普通件候选（含不同方向）的数量，
        // 是本搜索树实际可分支的数量，比普通件总件数更适合作为宽度上限。
        int availableBlockCount = initialState.availableBlocks.length;
        if (availableBlockCount == 0) {
            return initialState;
        }

        Node bestNode = new Node();
        bestNode.state = initialState;
        // 固定优先件不计入分支差异，普通件已装载面积作为初始评分。
        bestNode.score = initialState.getPackedVolume();

        // 参考 solve() 的动态宽度策略：每个宽度阶段独立从根节点开始，
        // 阶段内部保留 beamWidth 个状态，完成后再扩大宽度。
        for (int beamWidth = initialBeamWidth;
             beamWidth > 0 && beamWidth <= PackingRuntimeConfig.maxBeamWidth()
                     && System.currentTimeMillis() < finishTime;
             beamWidth <<= 1) {

            // 当保留宽度已经超过候选数量的约三分之一时，继续扩大宽度
            // 会显著增加计算量，而候选差异有限，因此结束宽度扩展。
            // 初始宽度无论如何都执行一次，保证小规模案例仍有基本搜索。
            if (beamWidth > availableBlockCount / 3
                    && beamWidth != initialBeamWidth) {
                break;
            }

            Queue<Node> nodes = new LinkedList<>();
            Node root = new Node();
            root.state = initialState;
            root.score = initialState.getPackedVolume();
            nodes.add(root);

            while (!nodes.isEmpty() && System.currentTimeMillis() < finishTime) {
                int currentLevelSize = nodes.size();
                TreeSet<Node> offspring = new TreeSet<>();

                for (int i = 0; i < currentLevelSize; i++) {
                    if (System.currentTimeMillis() >= finishTime) {
                        break;
                    }

                    Node currentNode = nodes.poll();
                    ArrayList<Node> children = new ArrayList<>();
                    // 插入 Sp 时初始 State 已经包含固定的优先件，不能再用
                    // countPlacedBlock()==0 判断根节点；按当前阶段创建的 root
                    // 对象识别根节点，保证根节点始终生成 w*w 个候选。
                    int childCount = currentNode == root
                            ? beamWidth * beamWidth
                            : beamWidth;
                    blockSearch(currentNode.state, childCount, children, 1);

                    if (children.isEmpty()) {
                        // 当前状态没有可行普通件，使用实际装载面积更新
                        // 当前宽度阶段和全局搜索的最佳结果。
                        if (currentNode.state.getPackedVolume()
                                >= bestNode.state.getPackedVolume()) {
                            bestNode = currentNode;
                        }
                    } else {
                        for (Node child : children) {
                            // 修改原因：如果当前板材的时间片在搜索树到达
                            // 叶节点前耗尽，不能丢弃已经找到的可行插入结果。
                            // 这里保存实际已装载普通件面积最大的中间状态，
                            // 作为时间耗尽时的安全返回结果。
                            if (child.state.getPackedVolume()
                                    > bestNode.state.getPackedVolume()) {
                                bestNode = child;
                            }
                            // 每层全局只保留 beamWidth 个候选状态。
                            update(offspring, beamWidth, child);
                        }
                    }
                }

                for (Node node : offspring) {
                    nodes.add(node);
                }
            }

            // 当前宽度已经覆盖了全部候选时，没有必要继续扩大。
            if (beamWidth >= availableBlockCount || beamWidth >= PackingRuntimeConfig.maxBeamWidth()) {
                break;
            }
            // 防止极大候选数量导致左移溢出后重新进入负数循环。
            if (beamWidth > Integer.MAX_VALUE / 2) {
                break;
            }
        }

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
     * 返回 Q-learning 插入模式局部奖励使用的时间预算。
     *
     * @param deadlineMillis 插入阶段截止时间；{@link Long#MAX_VALUE} 表示无总时限。
     * @param insertionStartMillis 插入阶段开始时的系统时间戳，单位为毫秒。
     * @return 有总时限时返回阶段预算；无总时限时返回 0，表示奖励不施加时间惩罚。
     */
    private long insertionAllottedMillis(long deadlineMillis, long insertionStartMillis) {
        if (deadlineMillis == Long.MAX_VALUE) {
            return 0L;
        }
        return Math.max(1L, deadlineMillis - insertionStartMillis);
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

    private SpaceManager createResidualSpaceManager(BoardStateSnapshot snapshot) {
        // 修改原因：优先件插入阶段要求按剩余空隙面积降序选择空间，
        // 不再使用原先“靠近板材边角优先、面积次优”的比较器。
        Comparator<Space> comparator = new SpaceVolumeComparator();
        SpaceManager manager = new SpaceManager(comparator);
        manager.insert(new ArrayList<>(snapshot.getRemainingSpaces()));
        return manager;
    }

    /**
     * 尝试重排利用率最低的一张优先件板材，并保证失败时完整保留原板材快照。
     *
     * <p>该方法只处理普通件插入前的优先件布局。它不增加 Sp，不混入普通件，
     * 且只在全部优先件仍能回填同一张板材、布局合法并且最大连续空闲空间不变差时
     * 接受重排结果。</p>
     *
     * @param seedBoards 当前优先件阶段产生的全部 Sp 快照。
     * @param deadlineMillis 全局绝对截止时间；达到该时间时放弃重排并返回原快照。
     * @return 原列表副本，或其中一张最低利用率 Sp 被安全替换后的列表副本。
     */
    private List<BoardStateSnapshot> repackLowestUtilizationPriorityBoard(
            List<BoardStateSnapshot> seedBoards,
            long deadlineMillis) {
        List<BoardStateSnapshot> result = new ArrayList<>(seedBoards);
        if (result.isEmpty() || System.currentTimeMillis() >= deadlineMillis) {
            return result;
        }

        int targetIndex = findLowestUtilizationBoard(result);
        BoardStateSnapshot original = result.get(targetIndex);
        BoardStateSnapshot repacked = repackPriorityBoardAtLowerLeft(original, deadlineMillis);
        if (repacked != original) {
            result.set(targetIndex, repacked);
        }
        return result;
    }

    /**
     * 找到当前优先件占用面积比例最低的 Sp；并列时保留输入顺序。
     *
     * @param boards 仅包含优先件布局的 Sp 快照。
     * @return 最低利用率板材在 {@code boards} 中的索引。
     */
    private int findLowestUtilizationBoard(List<BoardStateSnapshot> boards) {
        int selectedIndex = 0;
        double selectedUtilization = Double.POSITIVE_INFINITY;
        double boardArea = Math.max(1.0, (double) inst.length * inst.width);
        for (int index = 0; index < boards.size(); index++) {
            double usedArea = 0.0;
            for (PlacedCuboid cuboid : boards.get(index).getPlacedCuboids()) {
                usedArea += Math.max(0.0, cuboid.getVolume());
            }
            double utilization = usedArea / boardArea;
            if (utilization < selectedUtilization) {
                selectedUtilization = utilization;
                selectedIndex = index;
            }
        }
        return selectedIndex;
    }

    /**
     * 用“最大剩余空间 + 左下角锚点”重排一张 Sp 中的全部优先件。
     *
     * @param original 待尝试重排的优先件板材快照。
     * @param deadlineMillis 全局绝对截止时间。
     * @return 成功且不降低最大连续空闲空间时返回新快照；否则返回 {@code original}。
     */
    private BoardStateSnapshot repackPriorityBoardAtLowerLeft(BoardStateSnapshot original,
                                                               long deadlineMillis) {
        List<PlacedCuboid> remaining = new ArrayList<>(original.getPlacedCuboids());
        if (remaining.isEmpty() || !containsOnlyPriorityPieces(remaining)) {
            return original;
        }
        remaining.sort((left, right) -> Double.compare(repackPriorityScore(right), repackPriorityScore(left)));

        List<PlacedCuboid> repacked = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            if (System.currentTimeMillis() >= deadlineMillis) {
                return original;
            }
            List<Space> spaces = SpaceManager.calculateResidualSpaces(inst.length, inst.width, repacked);
            spaces.sort((left, right) -> {
                int comparison = Double.compare(right.volume, left.volume);
                return comparison != 0
                        ? comparison
                        : Integer.compare(Math.max(right.length(), right.width()),
                        Math.max(left.length(), left.width()));
            });

            boolean placed = false;
            for (Space space : spaces) {
                int pieceIndex = firstFittingPriorityPiece(remaining, space);
                if (pieceIndex < 0) {
                    continue;
                }
                PlacedCuboid piece = remaining.remove(pieceIndex);
                // 这里明确使用极大空闲矩形左下角，不复用原有四角选择逻辑。
                repacked.add(new PlacedCuboid(space.x1, space.y1, piece.length, piece.width,
                        piece.box, piece.ortIdx));
                placed = true;
                break;
            }
            if (!placed) {
                return original;
            }
        }

        if (!isValidSingleBoardLayout(repacked)) {
            return original;
        }
        List<Space> repackedSpaces = SpaceManager.calculateResidualSpaces(inst.length, inst.width, repacked);
        double originalLargestSpace = largestResidualSpace(original.getRemainingSpaces());
        double repackedLargestSpace = largestResidualSpace(repackedSpaces);
        if (repackedLargestSpace + 1e-9 < originalLargestSpace) {
            return original;
        }
        return new BoardStateSnapshot(repacked, repackedSpaces);
    }

    /**
     * 在优先件列表中返回第一个能放入指定空间的候选位置。
     *
     * @param pieces 按优先级评分降序排列的未放置优先件列表。
     * @param space 当前尝试的极大空闲空间。
     * @return 可放置候选的列表索引；不存在时返回 {@code -1}。
     */
    private int firstFittingPriorityPiece(List<PlacedCuboid> pieces, Space space) {
        for (int index = 0; index < pieces.size(); index++) {
            PlacedCuboid piece = pieces.get(index);
            if (piece.length <= space.length() && piece.width <= space.width()) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 校验重排后的优先件布局仍在单张板材内且任意两件不重叠。
     *
     * @param pieces 重排后的优先件放置列表。
     * @return 布局合法时返回 {@code true}。
     */
    private boolean isValidSingleBoardLayout(List<PlacedCuboid> pieces) {
        for (int first = 0; first < pieces.size(); first++) {
            PlacedCuboid left = pieces.get(first);
            if (left.x < 0 || left.y < 0
                    || left.x + left.length > inst.length
                    || left.y + left.width > inst.width) {
                return false;
            }
            for (int second = first + 1; second < pieces.size(); second++) {
                PlacedCuboid right = pieces.get(second);
                boolean overlap = left.x < right.x + right.length
                        && left.x + left.length > right.x
                        && left.y < right.y + right.width
                        && left.y + left.width > right.y;
                if (overlap) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 判断快照是否仍只包含优先件；防止后续维护时误把已插入普通件的板材交给重排。
     *
     * @param pieces 板材中的全部放置矩形。
     * @return 全部矩形均表示优先件时返回 {@code true}。
     */
    private boolean containsOnlyPriorityPieces(List<PlacedCuboid> pieces) {
        for (PlacedCuboid piece : pieces) {
            if (piece.box == null || !("1".equals(piece.box.color)
                    || "true".equalsIgnoreCase(piece.box.color))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算重排时使用的优先件排序分数，保持大件优先并兼顾相对板材尺寸。
     *
     * @param piece 当前待重排的优先件矩形。
     * @return 分数越大，越优先放入最大剩余空间。
     */
    private double repackPriorityScore(PlacedCuboid piece) {
        double normalizedLength = (double) piece.length / Math.max(1, inst.length);
        double normalizedWidth = (double) piece.width / Math.max(1, inst.width);
        return ((double) piece.length * piece.width)
                * (1.0 + normalizedLength * normalizedLength + normalizedWidth * normalizedWidth);
    }

    /**
     * 返回空间列表中最大的矩形空隙面积。
     *
     * @param spaces 待统计的剩余空间列表。
     * @return 最大矩形空隙面积；列表为空时返回零。
     */
    private double largestResidualSpace(List<Space> spaces) {
        double largest = 0.0;
        for (Space space : spaces) {
            largest = Math.max(largest, space.volume);
        }
        return largest;
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

    private PlacedBlock[] createPlacedBlocks(BoardStateSnapshot snapshot) {
        List<PlacedCuboid> placedCuboids = snapshot.getPlacedCuboids();
        PlacedBlock[] placedBlocks = new PlacedBlock[placedCuboids.size()];

        for (int i = 0; i < placedCuboids.size(); i++) {
            PlacedCuboid placedCuboid = placedCuboids.get(i);
            int orientationIndex = findOrientationIndex(placedCuboid);
            GeneralBlock block = new GeneralBlock(
                    1,
                    1,
                    placedCuboid.box,
                    orientationIndex,
                    inst.boxes.length);
            placedBlocks[i] = new PlacedBlock(placedCuboid.x, placedCuboid.y, block);
        }
        return placedBlocks;
    }

    private int findOrientationIndex(PlacedCuboid placedCuboid) {
        if (placedCuboid.box.variation != null) {
            for (int i = 0; i < placedCuboid.box.variation.length; i++) {
                int length = placedCuboid.box.variation[i][1];
                int width = placedCuboid.box.variation[i][0];
                if (length == placedCuboid.length && width == placedCuboid.width) {
                    return i;
                }
            }
        }

        if (placedCuboid.ortIdx >= 0
                && placedCuboid.box.variation != null
                && placedCuboid.ortIdx < placedCuboid.box.variation.length) {
            return placedCuboid.ortIdx;
        }
        return 0;
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

    private boolean hasNoAllowedBoxes(int[] freeBoxes, boolean[] allowedTypes) {
        for (int i = 0; i < freeBoxes.length; i++) {
            boolean allowed = allowedTypes == null
                    || (i < allowedTypes.length && allowedTypes[i]);
            if (allowed && freeBoxes[i] > 0) {
                return false;
            }
        }
        return true;
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
            WIDTH = allBlocks.length / 3;
        }
        State state;
        state = State.createMultipleInitState(newInst, spaceManager, allBlocks, containerNum);

        Queue<Node> newNodes = new LinkedList<>();
        Node root = new Node();
        root.state = state;
        newNodes.add(root);
        while (!newNodes.isEmpty() && newNodes != null) {
            int limit = newNodes.size();
            TreeSet<Node> offspring = new TreeSet<Node>();
            for (int i = 0; i < limit; i++) {
                Node curNode = newNodes.poll();
                ArrayList<Node> curChild = new ArrayList<Node>();
                int childCount = (curNode.state.countPlacedBlock() == 0 ? WIDTH * WIDTH : WIDTH);
                blockSearch(curNode.state, childCount, curChild, 1);
                if (curChild.isEmpty()) {
                    if (curNode.score >= bestNode.score) {
                        bestNode = curNode;
                    }
                } else {
                    for (int j = 0; j < curChild.size(); j++) {
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

        for (int i = 0; i < bestNode.state.getFreeBoxes().length; i++) {
            if (bestNode.state.getFreeBoxes()[i] > 0) {
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
     * 下一张可删除的板材，直到达到时间上限或连续多轮完整扫描都没有减板。</p>
     */
    public ExecutionResult ImproveByRepack(ExecutionResult executionResult, double maxTime, Random random) {
        System.out.println("Start improving solution by repacking.");
        long startTime = System.currentTimeMillis();
        int containerNum = executionResult.solutions.size();
        List<Integer> locations = new ArrayList<>();

        int location;
        // 修改原因：flag 只记录了若干次“没有可选板材”，不能明确表示
        // 是否已经完整扫描过所有低利用率板材。这里改为记录完整无减板轮数。
        int noImprovementSweeps = 0;

        while (true) {
            // 时间上限仍然是全局优化的第一停止条件。
            if ((System.currentTimeMillis() - startTime) * 0.001 >= maxTime) {
                break;
            }

            // 双重停止条件：连续完成多轮完整扫描且没有减少板材数量时，
            // 即使尚未耗尽时间，也停止对当前结果继续进行无效重排。
            if (noImprovementSweeps >= MAX_NO_IMPROVEMENT_SWEEPS) {
                break;
            }

            // 修改原因：减板后 solutions 数量会变化，不能继续使用初始板材数。
            // 每轮重新读取数量，保证后续索引和候选集合与当前结果一致。
            containerNum = executionResult.solutions.size();
            if (containerNum <= 1) {
                // 只剩一张板材时没有继续减板的可能。
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
                System.out.println("Completed repack sweep without reducing board count. "
                        + "No-reduction sweeps: " + noImprovementSweeps
                        + "/" + MAX_NO_IMPROVEMENT_SWEEPS);
                if (noImprovementSweeps >= MAX_NO_IMPROVEMENT_SWEEPS) {
                    // 这是无改进保护，不替代前面的时间限制。
                    break;
                }
                continue;
            }

            // 只有确认当前板材满足低利用率条件后才记录，避免把 -1
            // 或不符合条件的索引混入本轮已尝试集合。
            locations.add(location);

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

            // 修改原因：当前板材已经计入 locations，候选为空时只应跳过
            // 当前板材，继续扫描本轮其他低利用率板材。
            if (pairSet.isEmpty()) {
                continue;
            }

            Collections.shuffle(pairSet, random);
            int iteration = 0;
            int index = 0;
            // 记录当前 pairSet 遍历是否产生过改进；它只控制是否重新
            // 从第一个候选开始，不再承担全局优化的停止职责。
            boolean improvedInCurrentPass = false;
            boolean currentBoardImproved = false;

            // 修改原因：删除原来的 maxIter=1000 硬截止。当前候选有限，
            // 无改进时会在完整遍历后退出；有改进时重新遍历，最终由时间
            // 上限或完整无改进遍历控制，避免在 1000 次时提前截断。
            while (unplacedBox.size() > 0) {
                if ((System.currentTimeMillis() - startTime) * 0.001 >= maxTime) {
                    break;
                }

                iteration++;
                int a = pairSet.get(index) / containerNum;
                int b = pairSet.get(index) % containerNum;

                ArrayList<Box> boxs = new ArrayList<>(unplacedBox);

                for (int i = 0; i < newSolutions.get(a).getPlacedCuboid().size(); i++) {
                    boxs.add(newSolutions.get(a).getPlacedCuboid().get(i).box.copy());
                }
                if (a != b) {
                    for (int i = 0; i < newSolutions.get(b).getPlacedCuboid().size(); i++) {
                        boxs.add(newSolutions.get(b).getPlacedCuboid().get(i).box.copy());
                    }
                }

                Instance newInst = new Instance(inst, boxs);
                ExecutionResult newSol;
                if (a == b) {
                    newSol = multipSolve(newInst, 1, decNode);
                } else {
                    newSol = multipSolve(newInst, 2, decNode);
                }

                if (newSol.unplacedBoxesVol < unplacedBoxVol) {
                    System.out.println("iter" + iteration +
                            "\t\t Improve solution by repack " + unplacedBoxVol / inst.length / inst.width
                            + "->" + newSol.unplacedBoxesVol / inst.length / inst.width + " , unplacedBoxesSize:"
                            + newSol.unplacedBoxes.size());
                    System.out.println("currentRepackCostTime:" + ((System.currentTimeMillis() - startTime) * 0.001) + "s , currentRepackMaxTime:" + maxTime + "s");
                    unplacedBox = newSol.unplacedBoxes;
                    unplacedBoxVol = newSol.unplacedBoxesVol;
                    if (a == b) {
                        newSolutions.set(a, newSol.solutions.get(0));
                    } else {
                        newSolutions.set(a, newSol.solutions.get(0));
                        newSolutions.set(b, newSol.solutions.get(1));
                    }
                    // 记录当前板材的确发生过严格的重排改进。
                    currentBoardImproved = true;
                    improvedInCurrentPass = true;
                }

                if (index < pairSet.size() - 1) {
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

                // 修改原因：减板后平均利用率和板材数量都已变化，立即
                // 更新状态并清空旧索引，下一轮必须基于新解重新选择板材。
                executionResult.setAvgUtilization();
                containerNum = executionResult.solutions.size();
                locations.clear();
                noImprovementSweeps = 0;
                continue;
            }

            // 时间耗尽时不再调用 getSolutions() 进行额外的重建排样，
            // 避免已经达到预算后又执行一轮不可中断的求解。
            if (!currentBoardImproved
                    || (System.currentTimeMillis() - startTime) * 0.001 >= maxTime) {
                continue;
            }

            ArrayList<Solution> solutions = getSolutions(unplacedBox);
            if (solutions.size() == 1) {
                executionResult.solutions = newSolutions;
                executionResult.solutions.add(location, solutions.get(0));

                // 当前板材未被删除，但其余板材已经发生严格改进。
                // 修改原因：板材数量和索引没有变化，保留 locations 可以让
                // 当前完整扫描继续处理其他板材；同数量重排也不能清零
                // noImprovementSweeps，否则小规模案例可能反复重排到时间上限。
                executionResult.setAvgUtilization();
            }
        }
        return executionResult;
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
     * 按当前配置扩展一个 Beam Search 状态；关闭 Q-learning 时执行原有候选生成逻辑。
     *
     * @param state 当前待扩展状态。
     * @param width 当前节点允许生成的最大子节点数量。
     * @param child 下一层候选节点收集器。
     * @param volumeType 完整度评估所使用的体积类型。
     */
    private void blockSearch(State state, int width, ArrayList<Node> child, int volumeType) {
        if (state.hasFreeSpace()) {
            if (qFillInsertionPolicy != null) {
                expandByQFillPolicy(state, width, child, volumeType);
                return;
            }
            if (qPackingPolicy != null) {
                expandByQPolicy(state, width, child, volumeType);
                return;
            }

            Space space = state.chooseBestSpace();
            List<GeneralBlock> candidate = state.chooseBestBlocks(space, width);

            if (candidate.size() == 0) {
                State newState = state.deleteSpace(space);
                blockSearch(newState, width, child, volumeType);
            } else {
                for (GeneralBlock pb : candidate) {
                    State newState = state.packBlock(space, pb);
                    Node newNode = new Node();
                    newNode.state = newState;
                    newNode.score = completeSolution(newNode.state, volumeType);
                    child.add(newNode);
                }
            }
        }
    }

    /**
     * 使用 Q-learning 策略选择候选空闲区和块，并将所得子节点加入 Beam Search 的下一层。
     * 当当前状态没有可放置块时，仍沿用原算法的“删除当前空闲区后继续搜索”语义。
     *
     * @param state 当前 Beam Search 状态。
     * @param width 当前节点允许生成的最大子节点数量。
     * @param child 用于收集下一层候选节点的列表。
     * @param volumeType 完整度评估所使用的体积类型。
     */
    private void expandByQPolicy(State state, int width, ArrayList<Node> child, int volumeType) {
        QPackingPolicy.Decision decision = qPackingPolicy.select(state, width);
        if (decision.moves().isEmpty()) {
            qPackingPolicy.observe(decision, List.of());
            State newState = state.deleteSpace(decision.discardSpace());
            blockSearch(newState, width, child, volumeType);
            return;
        }

        List<State> successors = new ArrayList<>(decision.moves().size());
        for (QPackingPolicy.Move move : decision.moves()) {
            State newState = state.packBlock(move.space(), move.block());
            successors.add(newState);

            Node newNode = new Node();
            newNode.state = newState;
            newNode.score = completeSolution(newNode.state, volumeType);
            child.add(newNode);
        }
        qPackingPolicy.observe(decision, successors);
    }

    /**
     * 使用“插入模式—空间策略—物品策略”三层 Q-learning 扩展一个 Sp 填充节点。
     *
     * @param state 当前包含固定优先件与部分普通件的搜索状态。
     * @param width 当前节点允许生成的最大后继数量。
     * @param child 用于收集下一层 Beam 节点的可变列表。
     * @param volumeType 完整度评估所使用的体积类型。
     */
    private void expandByQFillPolicy(State state, int width, ArrayList<Node> child, int volumeType) {
        QFillInsertionPolicy.Decision decision = qFillInsertionPolicy.select(state, width);
        if (decision.moves().isEmpty()) {
            qFillInsertionPolicy.observe(decision, List.of());
            State newState = state.deleteSpace(decision.discardSpace());
            blockSearch(newState, width, child, volumeType);
            return;
        }

        List<State> successors = new ArrayList<>(decision.moves().size());
        for (QFillInsertionPolicy.Move move : decision.moves()) {
            State newState = packInsertionBlock(state, move.space(), move.block());
            successors.add(newState);

            Node newNode = new Node();
            newNode.state = newState;
            newNode.score = completeSolution(newNode.state, volumeType);
            child.add(newNode);
        }
        qFillInsertionPolicy.observe(decision, successors);
    }

    /**
     * 按当前插入模式将普通件放入极大空闲空间。
     *
     * @param state 放置前的搜索状态。
     * @param space 已通过尺寸可行性检查的极大空闲空间。
     * @param block 待插入的普通件候选。
     * @return 放置后的新搜索状态。
     */
    private State packInsertionBlock(State state, Space space, GeneralBlock block) {
        if (activeInsertionAnchor == PlacementAnchor.NEAREST_BOARD_CORNER) {
            space.cornerDistance(inst.length, inst.width);
        }
        return state.packBlock(space, block, activeInsertionAnchor);
    }

    private double completeSolution(State initState, int volumeType) {
        double score = 0;
        State state = initState;
        while (state.hasFreeSpace()) {
            Space space = state.chooseBestSpace();
            GeneralBlock block = state.chooseBestBlock(space);
            if (block == null) {
                state = state.deleteSpace(space);
            } else {
                state = qFillInsertionPolicy == null
                        ? state.packBlock(space, block)
                        : packInsertionBlock(state, space, block);
            }
        }
        if (volumeType == 0) {
            score = state.getScoreVolume();
        } else if (volumeType == 1) {
            score = state.getPackedVolume();
        }

        return score;
    }

    private ArrayList<Solution> getSolutions(ArrayList<Box> unplacedBoxes) {
        ArrayList<Solution> solutions = new ArrayList<>();
        Instance instance = new Instance(inst, unplacedBoxes);
        GeneralBlock[] allBlocks = new BlockGenerator(instance).generateSingleBlock(true);
        State endState = null;
        int containerNum = 1;
        while (allBlocks.length > 0) {
            State state;
            if (containerNum == 1) {
                state = State.createMultipleInitState(instance, spaceManager, allBlocks, 1);
            } else {
                state = State.initState(endState, spaceManager);
            }
            while (state.hasFreeSpace()) {
                Space space = state.chooseBestSpace();
                GeneralBlock block = state.chooseBestBlock(space);
                if (block == null) {
                    state = state.deleteSpace(space);
                } else {
                    state = state.packBlock(space, block);
                }
            }
            allBlocks = state.availableBlocks;
            endState = state;
            containerNum++;
            Solution newSolution = state.toSolution();

            solutions.add(newSolution);
        }
        return solutions;
    }
}
