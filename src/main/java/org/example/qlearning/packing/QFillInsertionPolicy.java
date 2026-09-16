package org.example.qlearning.packing;

import org.example.beamsearch.blockgenerator.GeneralBlock;
import org.example.beamsearch.common.BoardStateSnapshot;
import org.example.beamsearch.common.Box;
import org.example.beamsearch.common.Instance;
import org.example.beamsearch.common.PlacedCuboid;
import org.example.beamsearch.common.PlacementAnchor;
import org.example.beamsearch.common.Space;
import org.example.beamsearch.state.State;
import org.example.qlearning.QLearningConfig;
import org.example.qlearning.QLearningSession;
import org.example.qlearning.SearchPhase;
import org.example.qlearning.TabularQController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 普通件插入既有优先件板材（Sp）时使用的分层 Q-learning 策略。
 *
 * <p>第一层在整个插入阶段开始前选择一次插入模式。对于旧四角、最低利用率 Sp
 * 重排和左下角三种模式，第二层在每个 Beam 节点对极大空闲空间排序，第三层对
 * 可放置普通件排序；“候选件整体重排”模式改由 BeamSearch 对一个 Sp 和一个候选件
 * 做完整单板验证，因此不执行空间与物品两层动作。该类不放宽任何几何约束。</p>
 */
public final class QFillInsertionPolicy {

    private static final boolean[] MODE_ACTIONS = allActions(FillInsertionMode.values().length);
    private static final boolean[] SPACE_ACTIONS = allActions(FillSpaceAction.values().length);
    private static final boolean[] ITEM_ACTIONS = allActions(FillItemAction.values().length);

    private final QLearningSession session;
    private final QLearningConfig config;
    private final Instance instance;
    private final TabularQController modeController;
    private final TabularQController spaceController;
    private final TabularQController itemController;
    private final double ordinaryMaterialArea;

    /**
     * 创建一个仅服务于 Sp 普通件填充阶段的分层策略。
     *
     * @param session 当前案例共享的 Q-learning 会话。
     * @param instance 包含优先件和普通件的混合排样实例。
     */
    public QFillInsertionPolicy(QLearningSession session, Instance instance) {
        this.session = session;
        this.config = session.config();
        this.instance = instance;
        this.modeController = session.controller(SearchPhase.FILL_MODE);
        this.spaceController = session.controller(SearchPhase.FILL_SPACE);
        this.itemController = session.controller(SearchPhase.FILL_ITEM);
        this.ordinaryMaterialArea = calculateOrdinaryMaterialArea(instance);
    }

    /**
     * 在 Sp 填充开始前选择一次整体插入模式。
     *
     * @param seedBoards 优先件阶段完成后的 Sp 板材快照。
     * @return 包含模式动作、离散状态与初始连续空间特征的决策记录。
     */
    public ModeDecision selectMode(List<BoardStateSnapshot> seedBoards) {
        int stateKey = encodeModeState(seedBoards);
        int actionIndex = modeController.selectAction(stateKey, MODE_ACTIONS);
        session.recordDecision(SearchPhase.FILL_MODE, stateKey, actionIndex);
        return new ModeDecision(stateKey,
                FillInsertionMode.values()[actionIndex],
                largestResidualRatio(seedBoards));
    }

    /**
     * 返回一个插入模式应使用的普通件放置锚点。
     *
     * @param mode 当前已选择的插入模式。
     * @return 普通件在极大空闲空间内的坐标锚点。
     */
    public PlacementAnchor placementAnchorFor(FillInsertionMode mode) {
        return mode == FillInsertionMode.LOWER_LEFT_INSERTION
                ? PlacementAnchor.SPACE_LOWER_LEFT
                : PlacementAnchor.NEAREST_BOARD_CORNER;
    }

    /**
     * 在一个 Beam 节点中依次选择空间排序动作与物品排序动作。
     *
     * @param state 当前插入搜索状态。
     * @param maxMoves 最多返回的可扩展空间—物品组合数，通常等于 Beam 分支宽度。
     * @return 已排序的候选移动；没有可行候选时提供应删除的空间。
     */
    public Decision select(State state, int maxMoves) {
        List<Space> allSpaces = state.getAllCandidateSpaces();
        if (allSpaces.isEmpty()) {
            Space discardSpace = state.hasFreeSpace() ? state.chooseBestSpace() : null;
            return new Decision(List.of(), discardSpace, null, List.of(), potential(state));
        }

        int spaceStateKey = encodeSpaceState(state, allSpaces);
        int spaceActionIndex = spaceController.selectAction(spaceStateKey, SPACE_ACTIONS);
        FillSpaceAction spaceAction = FillSpaceAction.values()[spaceActionIndex];
        session.recordDecision(SearchPhase.FILL_SPACE, spaceStateKey, spaceActionIndex);
        allSpaces.sort(spaceComparator(spaceAction));
        List<SpaceCandidate> candidates = enumerateFeasibleSpaces(state, allSpaces);
        if (candidates.isEmpty()) {
            return new Decision(List.of(),
                    allSpaces.get(0),
                    new LocalReference(SearchPhase.FILL_SPACE, spaceStateKey, spaceActionIndex),
                    List.of(),
                    potential(state));
        }

        int spaceLimit = config.limitCandidateSet()
                ? Math.min(config.maxCandidateSpaces(), candidates.size())
                : candidates.size();
        List<RankedSpace> rankedSpaces = new ArrayList<>(spaceLimit);
        List<LocalReference> itemReferences = new ArrayList<>();
        for (int index = 0; index < spaceLimit; index++) {
            SpaceCandidate candidate = candidates.get(index);
            int itemStateKey = encodeItemState(state, candidate);
            int itemActionIndex = itemController.selectAction(itemStateKey, ITEM_ACTIONS);
            FillItemAction itemAction = FillItemAction.values()[itemActionIndex];
            session.recordDecision(SearchPhase.FILL_ITEM, itemStateKey, itemActionIndex);

            List<GeneralBlock> blocks = new ArrayList<>(candidate.blocks());
            blocks.sort(itemComparator(itemAction));
            int blockLimit = config.limitCandidateSet()
                    ? Math.min(config.maxCandidateBlocksPerSpace(), blocks.size())
                    : blocks.size();
            rankedSpaces.add(new RankedSpace(candidate.space(), blocks.subList(0, blockLimit)));
            itemReferences.add(new LocalReference(SearchPhase.FILL_ITEM, itemStateKey, itemActionIndex));
        }

        List<Move> moves = interleaveMoves(rankedSpaces, Math.max(1, maxMoves));
        return new Decision(moves,
                candidates.get(0).space(),
                new LocalReference(SearchPhase.FILL_SPACE, spaceStateKey, spaceActionIndex),
                itemReferences,
                potential(state));
    }

    /**
     * 根据当前扩展产生的后继状态更新空间和物品两层 Q 值。
     *
     * @param decision {@link #select(State, int)} 返回的决策记录。
     * @param successors 本次实际生成的后继搜索状态。
     */
    public void observe(Decision decision, List<State> successors) {
        if (decision == null || decision.spaceReference() == null) {
            return;
        }
        boolean noSuccessor = successors == null || successors.isEmpty();
        State bestSuccessor = noSuccessor ? null : bestPotentialSuccessor(successors);
        List<Space> nextSpaces = noSuccessor ? List.of() : bestSuccessor.getAllCandidateSpaces();
        boolean noFurtherMove = !noSuccessor && enumerateFeasibleSpaces(bestSuccessor, nextSpaces).isEmpty();
        boolean terminal = noSuccessor || noFurtherMove;
        double nextPotential = noSuccessor ? decision.potentialBefore() - 0.50 : potential(bestSuccessor);
        double reward = clamp(nextPotential - decision.potentialBefore(), -0.25, 0.25);
        if (noSuccessor) {
            reward = -0.50;
        }

        int nextSpaceState = terminal ? 0 : encodeSpaceState(bestSuccessor, nextSpaces);
        updateAndTrace(spaceController,
                decision.spaceReference(),
                reward,
                nextSpaceState,
                terminal,
                SPACE_ACTIONS,
                decision.moves().size(),
                FillSpaceAction.values()[decision.spaceReference().actionIndex()].name());

        for (LocalReference reference : decision.itemReferences()) {
            int nextItemState = terminal ? 0 : encodeItemState(bestSuccessor, firstFeasibleSpace(bestSuccessor));
            updateAndTrace(itemController,
                    reference,
                    reward,
                    nextItemState,
                    terminal,
                    ITEM_ACTIONS,
                    decision.moves().size(),
                    FillItemAction.values()[reference.actionIndex()].name());
        }
    }

    /**
     * 在 Sp 填充结束后更新插入模式 Q 值。
     *
     * @param decision 填充开始前选择的模式决策。
     * @param outputBoards 普通件插入后的所有 Sp 板材快照。
     * @param elapsedMillis 填充阶段的实际耗时，单位为毫秒。
     * @param allottedMillis 填充阶段可使用的时间预算，单位为毫秒。
     */
    public void observeMode(ModeDecision decision,
                            List<BoardStateSnapshot> outputBoards,
                            long elapsedMillis,
                            long allottedMillis) {
        if (decision == null) {
            return;
        }
        int nextStateKey = encodeModeState(outputBoards);
        double insertedFraction = ordinaryMaterialArea <= 0.0
                ? 0.0
                : clamp(calculateInsertedOrdinaryArea(outputBoards) / ordinaryMaterialArea, 0.0, 1.0);
        double continuousSpaceChange = largestResidualRatio(outputBoards) - decision.initialLargestResidualRatio();
        double timeFraction = allottedMillis <= 0L
                ? 0.0
                : clamp((double) elapsedMillis / allottedMillis, 0.0, 1.0);
        double reward = clamp(0.75 * insertedFraction
                + 0.20 * continuousSpaceChange
                - 0.05 * timeFraction, -1.0, 1.0);

        modeController.update(decision.stateKey(), decision.mode().ordinal(), reward,
                nextStateKey, true, null);
        session.trace(SearchPhase.FILL_MODE,
                decision.stateKey(),
                decision.mode().name(),
                modeController.getEpsilon(),
                reward,
                nextStateKey,
                true,
                outputBoards == null ? 0 : outputBoards.size());
    }

    /**
     * 对 Sp 板材列表按当前空间动作指定的最大剩余空间指标排序。
     *
     * @param boards 尚未执行普通件插入的 Sp 快照列表。
     * @param action 当前空间排序动作。
     * @return 不修改原列表的新排序列表。
     */
    public List<BoardStateSnapshot> orderBoards(List<BoardStateSnapshot> boards,
                                                 FillSpaceAction action) {
        List<BoardStateSnapshot> ordered = new ArrayList<>(boards == null ? List.of() : boards);
        ordered.sort((left, right) -> compareBoardResidual(right, left, action));
        return ordered;
    }

    /**
     * 返回空间排序动作对应的比较器，供 BeamSearch 的剩余空间管理器使用。
     *
     * @param action 当前空间排序动作。
     * @return 从优到劣排序极大空闲矩形的比较器。
     */
    public Comparator<Space> spaceComparator(FillSpaceAction action) {
        return (left, right) -> {
            int comparison;
            if (action == FillSpaceAction.LONGEST_EDGE_SPACE) {
                comparison = Integer.compare(longestEdge(right), longestEdge(left));
                if (comparison != 0) {
                    return comparison;
                }
            }
            comparison = Double.compare(right.volume, left.volume);
            if (comparison != 0) {
                return comparison;
            }
            return Integer.compare(longestEdge(right), longestEdge(left));
        };
    }

    /**
     * 记录一次插入模式选择。
     *
     * @param stateKey 选择时的离散状态。
     * @param mode 被选中的阶段级插入模式。
     * @param initialLargestResidualRatio 选择前所有 Sp 中最大连续空间的归一化比例。
     */
    public record ModeDecision(int stateKey,
                               FillInsertionMode mode,
                               double initialLargestResidualRatio) {
    }

    /**
     * 一个已通过尺寸可行性检查的空间—物品组合。
     *
     * @param space 放置所在的极大空闲矩形。
     * @param block 将要放入该空间的普通件候选。
     */
    public record Move(Space space, GeneralBlock block) {
    }

    /**
     * 一次 Beam 节点扩展所需的分层决策记录。
     *
     * @param moves 供 Beam Search 创建后继节点的有序组合。
     * @param discardSpace 没有可行组合时应删除的空闲空间。
     * @param spaceReference 空间动作的 Q 表引用。
     * @param itemReferences 各候选空间对应的物品动作 Q 表引用。
     * @param potentialBefore 执行动作前的局部势函数值。
     */
    public record Decision(List<Move> moves,
                           Space discardSpace,
                           LocalReference spaceReference,
                           List<LocalReference> itemReferences,
                           double potentialBefore) {
    }

    /** 记录一个动作所属的 Q 表、状态和动作索引，供局部与终局奖励使用。 */
    public record LocalReference(SearchPhase phase, int stateKey, int actionIndex) {
    }

    /**
     * 枚举当前状态中至少能容纳一个普通件的极大空闲空间。
     *
     * @param state 当前 Beam 搜索状态。
     * @param orderedSpaces 已按当前空间动作排序的极大空闲空间。
     * @return 每个空间及其可放置普通件列表组成的候选集合。
     */
    private List<SpaceCandidate> enumerateFeasibleSpaces(State state, List<Space> orderedSpaces) {
        List<SpaceCandidate> candidates = new ArrayList<>();
        int scannedSpaceCount = 0;
        for (Space space : orderedSpaces) {
            if (config.limitCandidateSet()
                    && scannedSpaceCount++ >= config.fillCandidateSpaceScanLimit()) {
                break;
            }
            List<GeneralBlock> feasibleBlocks = state.getFeasibleBlocks(space, Integer.MAX_VALUE);
            if (!feasibleBlocks.isEmpty()) {
                candidates.add(new SpaceCandidate(space, feasibleBlocks));
                if (config.limitCandidateSet()
                        && candidates.size() >= config.maxCandidateSpaces()) {
                    break;
                }
            }
        }
        return candidates;
    }

    /**
     * 将物品动作转换为对候选普通件的稳定排序比较器。
     *
     * @param action 当前物品排序动作。
     * @return 从优到劣排序普通件候选的比较器。
     */
    private Comparator<GeneralBlock> itemComparator(FillItemAction action) {
        return (left, right) -> {
            int comparison;
            if (action == FillItemAction.LONGEST_EDGE_ITEM) {
                comparison = Integer.compare(longestEdge(right), longestEdge(left));
            } else if (action == FillItemAction.MAX_PRIORITY_SCORE_ITEM) {
                comparison = Double.compare(priorityScore(right), priorityScore(left));
            } else {
                comparison = Long.compare(right.blockVolume, left.blockVolume);
            }
            if (comparison != 0) {
                return comparison;
            }
            comparison = Long.compare(right.blockVolume, left.blockVolume);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(longestEdge(right), longestEdge(left));
            if (comparison != 0) {
                return comparison;
            }
            return Double.compare(priorityScore(right), priorityScore(left));
        };
    }

    /**
     * 以轮转方式从多个优先空间抽取候选，避免一个空间占满所有 Beam 分支。
     *
     * @param rankedSpaces 已完成空间和物品排序的候选空间列表。
     * @param maxMoves 允许返回的最大空间—物品组合数。
     * @return 供 Beam Search 扩展的有序组合。
     */
    private List<Move> interleaveMoves(List<RankedSpace> rankedSpaces, int maxMoves) {
        List<Move> moves = new ArrayList<>();
        for (int rank = 0; moves.size() < maxMoves; rank++) {
            boolean added = false;
            for (RankedSpace rankedSpace : rankedSpaces) {
                if (rank < rankedSpace.blocks().size()) {
                    moves.add(new Move(rankedSpace.space(), rankedSpace.blocks().get(rank)));
                    added = true;
                    if (moves.size() >= maxMoves) {
                        break;
                    }
                }
            }
            if (!added) {
                break;
            }
        }
        return moves;
    }

    /**
     * 同时执行一次局部 Q 值更新与可选轨迹写出。
     *
     * @param controller 当前动作所属的 Q 控制器。
     * @param reference 动作执行时记录的状态与动作索引。
     * @param reward 本次候选扩展得到的局部奖励。
     * @param nextStateKey 后继状态的离散编号。
     * @param terminal 当前扩展是否已无可行后继。
     * @param nextActions 后继状态可选的动作掩码；终止状态可为 {@code null}。
     * @param candidateCount 当前实际生成的候选组合数。
     * @param actionName 用于轨迹文件的可读动作名称。
     */
    private void updateAndTrace(TabularQController controller,
                                LocalReference reference,
                                double reward,
                                int nextStateKey,
                                boolean terminal,
                                boolean[] nextActions,
                                int candidateCount,
                                String actionName) {
        controller.update(reference.stateKey(), reference.actionIndex(), reward, nextStateKey,
                terminal, terminal ? null : nextActions);
        session.trace(reference.phase(), reference.stateKey(), actionName, controller.getEpsilon(),
                reward, nextStateKey, terminal, candidateCount);
    }

    /**
     * 将整个 Sp 插入阶段的板材形态离散编码为插入模式状态。
     *
     * @param boards 普通件插入前或插入后的 Sp 板材快照。
     * @return 由五个三档特征组成的状态编号，取值范围为 {@code [0, 242]}。
     */
    private int encodeModeState(List<BoardStateSnapshot> boards) {
        int boardCountBin = bin(boards == null ? 0 : boards.size(), 1, 4);
        double[] utilizationStats = utilizationStats(boards);
        int minUtilizationBin = bin(utilizationStats[0], 0.45, 0.75);
        int maxSpaceBin = bin(largestResidualRatio(boards), 0.20, 0.50);
        int fragmentationBin = bin(averageFragmentation(boards), 0.25, 0.55);
        int ordinaryFitBin = bin(ordinaryMaterialArea / Math.max(1.0, totalResidualArea(boards)), 0.50, 1.20);
        return boardCountBin + 3 * minUtilizationBin + 9 * maxSpaceBin
                + 27 * fragmentationBin + 81 * ordinaryFitBin;
    }

    /**
     * 将当前 Beam 节点的剩余空间形态编码为空间策略状态。
     *
     * @param state 当前 Beam 搜索状态。
     * @param candidates 当前可容纳普通件的空间候选。
     * @return 由已填充比例、空闲比例、最大空间、碎片率和空间数量组成的状态编号。
     */
    private int encodeSpaceState(State state, List<Space> spaces) {
        double boardArea = boardArea();
        int packedBin = bin(state.getPackedVolume() / boardArea, 0.25, 0.60);
        int freeBin = bin(totalSpaceArea(spaces) / boardArea, 0.25, 0.60);
        int largestBin = bin(largestSpaceArea(spaces) / boardArea, 0.15, 0.40);
        int fragmentationBin = bin(fragmentation(spaces), 0.25, 0.55);
        int spaceCountBin = bin(spaces.size(), 2, 8);
        return packedBin + 3 * freeBin + 9 * largestBin + 27 * fragmentationBin + 81 * spaceCountBin;
    }

    /**
     * 将一个空间及其可放置普通件集合编码为物品策略状态。
     *
     * @param state 当前 Beam 搜索状态。
     * @param candidate 已选空间及该空间中的可放置普通件；为空时返回零状态。
     * @return 由填充比例、空间面积、长宽比、候选数量和尺寸匹配程度组成的状态编号。
     */
    private int encodeItemState(State state, SpaceCandidate candidate) {
        if (candidate == null) {
            return 0;
        }
        double boardArea = boardArea();
        double spaceArea = Math.max(1.0, candidate.space().volume);
        int packedBin = bin(state.getPackedVolume() / boardArea, 0.25, 0.60);
        int spaceAreaBin = bin(spaceArea / boardArea, 0.15, 0.40);
        int aspectBin = bin((double) Math.min(candidate.space().length(), candidate.space().width())
                / Math.max(1, longestEdge(candidate.space())), 0.25, 0.60);
        int candidateCountBin = bin(candidate.blocks().size(), 2, 6);
        long largestBlockArea = candidate.blocks().stream().mapToLong(block -> block.blockVolume).max().orElse(0L);
        int sizeFitBin = bin(largestBlockArea / spaceArea, 0.25, 0.60);
        return packedBin + 3 * spaceAreaBin + 9 * aspectBin
                + 27 * candidateCountBin + 81 * sizeFitBin;
    }

    /**
     * 返回后继状态中的第一个可用空间，用于构造物品动作的下一状态编码。
     *
     * @param state 当前后继 Beam 搜索状态。
     * @return 存在可放置普通件时的空间候选；不存在时返回 {@code null}。
     */
    private SpaceCandidate firstFeasibleSpace(State state) {
        List<SpaceCandidate> candidates = enumerateFeasibleSpaces(state, state.getAllCandidateSpaces());
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 在本次生成的后继中挑选局部势函数最大的状态作为 Q-learning 后继状态。
     *
     * @param successors 当前动作生成的全部可行后继状态。
     * @return 势函数值最高的后继状态。
     */
    private State bestPotentialSuccessor(List<State> successors) {
        State best = successors.get(0);
        double bestPotential = potential(best);
        for (int index = 1; index < successors.size(); index++) {
            double candidatePotential = potential(successors.get(index));
            if (candidatePotential > bestPotential) {
                best = successors.get(index);
                bestPotential = candidatePotential;
            }
        }
        return best;
    }

    /**
     * 计算用于局部奖励的状态势函数。
     *
     * @param state 待评估的插入搜索状态。
     * @return 综合普通件填充比例、最大连续空间与碎片率的有界局部质量值。
     */
    private double potential(State state) {
        if (state == null) {
            return -0.50;
        }
        List<Space> spaces = state.getAllCandidateSpaces();
        double boardArea = boardArea();
        double placedRatio = clamp(state.getPackedVolume() / boardArea, 0.0, 1.0);
        double largestRatio = clamp(largestSpaceArea(spaces) / boardArea, 0.0, 1.0);
        return 0.55 * placedRatio + 0.20 * largestRatio - 0.25 * fragmentation(spaces);
    }

    /**
     * 比较两张 Sp 的最大剩余空间指标。
     *
     * @param left 左侧板材快照。
     * @param right 右侧板材快照。
     * @param action 当前使用面积还是最长边作为空间指标。
     * @return 与 {@link Comparator#compare(Object, Object)} 一致的比较结果。
     */
    private int compareBoardResidual(BoardStateSnapshot left,
                                     BoardStateSnapshot right,
                                     FillSpaceAction action) {
        double leftMetric = boardResidualMetric(left, action);
        double rightMetric = boardResidualMetric(right, action);
        return Double.compare(leftMetric, rightMetric);
    }

    /**
     * 计算一张 Sp 在指定空间策略下的最大剩余空间指标。
     *
     * @param board 待评估的 Sp 快照。
     * @param action 面积优先或最长边优先的空间动作。
     * @return 该 Sp 中最佳剩余空间的对应指标。
     */
    private double boardResidualMetric(BoardStateSnapshot board, FillSpaceAction action) {
        double largest = 0.0;
        if (board != null) {
            for (Space space : board.getRemainingSpaces()) {
                double metric = action == FillSpaceAction.LONGEST_EDGE_SPACE
                        ? longestEdge(space)
                        : space.volume;
                largest = Math.max(largest, metric);
            }
        }
        return largest;
    }

    /**
     * 计算候选普通件的尺寸优先级评分。
     *
     * @param block 当前方向下的候选普通件矩形块。
     * @return {@code wh(1+w^2/W^2+h^2/H^2)} 的评分值。
     */
    private double priorityScore(GeneralBlock block) {
        double normalizedLength = (double) block.length / Math.max(1, instance.length);
        double normalizedWidth = (double) block.width / Math.max(1, instance.width);
        return block.blockVolume * (1.0 + normalizedLength * normalizedLength
                + normalizedWidth * normalizedWidth);
    }

    /**
     * 统计一个混合实例中全部普通件的实际材料面积。
     *
     * @param source 同时包含优先件与普通件的实例。
     * @return 普通件总材料面积。
     */
    private double calculateOrdinaryMaterialArea(Instance source) {
        if (source == null || source.boxes == null) {
            return 0.0;
        }
        double total = 0.0;
        for (Box box : source.boxes) {
            if (box != null && !isPriority(box)) {
                total += Math.max(0.0, box.volume) * Math.max(0, box.count);
            }
        }
        return total;
    }

    /**
     * 统计填充完成后实际进入 Sp 的普通件材料面积。
     *
     * @param boards 插入普通件后的 Sp 快照列表。
     * @return 已放入 Sp 的普通件材料面积。
     */
    private double calculateInsertedOrdinaryArea(List<BoardStateSnapshot> boards) {
        double total = 0.0;
        if (boards != null) {
            for (BoardStateSnapshot board : boards) {
                for (PlacedCuboid cuboid : board.getPlacedCuboids()) {
                    if (cuboid.box != null && !isPriority(cuboid.box)) {
                        total += Math.max(0.0, cuboid.getVolume());
                    }
                }
            }
        }
        return total;
    }

    /**
     * 计算 Sp 利用率的最小值和平均值。
     *
     * @param boards 待统计的 Sp 快照列表。
     * @return 长度为二的数组，依次表示最小利用率和平均利用率。
     */
    private double[] utilizationStats(List<BoardStateSnapshot> boards) {
        if (boards == null || boards.isEmpty()) {
            return new double[]{0.0, 0.0};
        }
        double min = 1.0;
        double sum = 0.0;
        for (BoardStateSnapshot board : boards) {
            double used = 0.0;
            for (PlacedCuboid cuboid : board.getPlacedCuboids()) {
                used += Math.max(0.0, cuboid.getVolume());
            }
            double utilization = clamp(used / boardArea(), 0.0, 1.0);
            min = Math.min(min, utilization);
            sum += utilization;
        }
        return new double[]{min, sum / boards.size()};
    }

    /**
     * 计算全部 Sp 中最大连续空闲矩形相对单张板材面积的比例。
     *
     * @param boards 待统计的 Sp 快照列表。
     * @return 归一化后的最大连续空闲空间比例。
     */
    private double largestResidualRatio(List<BoardStateSnapshot> boards) {
        double largest = 0.0;
        if (boards != null) {
            for (BoardStateSnapshot board : boards) {
                largest = Math.max(largest, largestSpaceArea(board.getRemainingSpaces()));
            }
        }
        return clamp(largest / boardArea(), 0.0, 1.0);
    }

    /**
     * 统计全部 Sp 的极大空闲矩形面积之和。
     *
     * @param boards 待统计的 Sp 快照列表。
     * @return 所有极大空闲矩形面积的累计值。
     */
    private double totalResidualArea(List<BoardStateSnapshot> boards) {
        double total = 0.0;
        if (boards != null) {
            for (BoardStateSnapshot board : boards) {
                total += totalSpaceArea(board.getRemainingSpaces());
            }
        }
        return total;
    }

    /**
     * 计算 Sp 列表的平均空间碎片率。
     *
     * @param boards 待统计的 Sp 快照列表。
     * @return 平均碎片率，范围为 {@code [0, 1]}。
     */
    private double averageFragmentation(List<BoardStateSnapshot> boards) {
        if (boards == null || boards.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (BoardStateSnapshot board : boards) {
            sum += fragmentation(board.getRemainingSpaces());
        }
        return sum / boards.size();
    }

    /**
     * 根据最大极大空间占全部极大空间面积的比例估计碎片率。
     *
     * @param spaces 当前板材的极大空闲空间列表。
     * @return 空间越分散数值越接近一的碎片率。
     */
    private double fragmentation(List<Space> spaces) {
        double total = totalSpaceArea(spaces);
        return total <= 0.0 ? 0.0 : clamp(1.0 - largestSpaceArea(spaces) / total, 0.0, 1.0);
    }

    /**
     * 汇总一组极大空闲矩形的面积。
     *
     * @param spaces 待汇总的空间列表。
     * @return 空间面积累计值；空列表返回零。
     */
    private double totalSpaceArea(List<Space> spaces) {
        return spaces == null ? 0.0 : spaces.stream().mapToDouble(space -> space.volume).sum();
    }

    /**
     * 返回一组极大空闲矩形中的最大面积。
     *
     * @param spaces 待统计的空间列表。
     * @return 最大空间面积；空列表返回零。
     */
    private double largestSpaceArea(List<Space> spaces) {
        return spaces == null ? 0.0 : spaces.stream().mapToDouble(space -> space.volume).max().orElse(0.0);
    }

    /**
     * 返回当前混合实例单张板材的安全面积。
     *
     * @return 至少为一的板材面积，用于避免归一化时除以零。
     */
    private double boardArea() {
        return Math.max(1.0, (double) instance.length * instance.width);
    }

    /**
     * 返回一个极大空闲矩形的最长边。
     *
     * @param space 待统计的极大空闲矩形。
     * @return 空间长度与宽度中的较大值。
     */
    private static int longestEdge(Space space) {
        return Math.max(space.length(), space.width());
    }

    /**
     * 返回一个候选普通件矩形块的最长边。
     *
     * @param block 待统计的候选普通件。
     * @return 候选块长度与宽度中的较大值。
     */
    private static int longestEdge(GeneralBlock block) {
        return Math.max(block.length, block.width);
    }

    /**
     * 判断一个工件是否为优先件。
     *
     * @param box 待判断的工件定义。
     * @return 颜色为 {@code 1} 或 {@code true} 时返回 {@code true}。
     */
    private static boolean isPriority(Box box) {
        return "1".equals(box.color) || "true".equalsIgnoreCase(box.color);
    }

    /**
     * 将连续数值按两个阈值离散为三个等级。
     *
     * @param value 待离散的连续值。
     * @param firstThreshold 第一档与第二档的分界值。
     * @param secondThreshold 第二档与第三档的分界值。
     * @return 0、1 或 2 三个离散等级之一。
     */
    private static int bin(double value, double firstThreshold, double secondThreshold) {
        return value <= firstThreshold ? 0 : value <= secondThreshold ? 1 : 2;
    }

    /**
     * 将整数数值按两个阈值离散为三个等级。
     *
     * @param value 待离散的整数值。
     * @param firstThreshold 第一档与第二档的分界值。
     * @param secondThreshold 第二档与第三档的分界值。
     * @return 0、1 或 2 三个离散等级之一。
     */
    private static int bin(int value, int firstThreshold, int secondThreshold) {
        return value <= firstThreshold ? 0 : value <= secondThreshold ? 1 : 2;
    }

    /**
     * 将数值限制在给定闭区间内。
     *
     * @param value 待限制的数值。
     * @param lower 区间下界。
     * @param upper 区间上界。
     * @return 位于 {@code [lower, upper]} 的数值。
     */
    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    /**
     * 创建一个所有动作均可选择的动作掩码。
     *
     * @param actionCount 动作总数。
     * @return 长度等于动作数且每一项均为 {@code true} 的掩码。
     */
    private static boolean[] allActions(int actionCount) {
        boolean[] actions = new boolean[actionCount];
        Arrays.fill(actions, true);
        return actions;
    }

    private record SpaceCandidate(Space space, List<GeneralBlock> blocks) {
    }

    private record RankedSpace(Space space, List<GeneralBlock> blocks) {
    }
}
