package org.example.qlearning.packing;

import org.example.beamsearch.blockgenerator.GeneralBlock;
import org.example.beamsearch.common.Instance;
import org.example.beamsearch.common.PlacedBlock;
import org.example.beamsearch.common.Space;
import org.example.beamsearch.state.State;
import org.example.qlearning.QLearningConfig;
import org.example.qlearning.QLearningSession;
import org.example.qlearning.SearchPhase;
import org.example.qlearning.TabularQController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 用 Q-learning 控制矩形 Beam Search 候选排序的策略。
 *
 * <p>本类只在现有 {@link State#packBlock(Space, GeneralBlock)} 之前决定候选顺序，
 * 不生成新坐标、不改变空间切分规则，也不放宽任何可行性约束。</p>
 */
public final class QPackingPolicy {

    private static final boolean[] ALL_ACTIONS_VALID;

    static {
        ALL_ACTIONS_VALID = new boolean[PackingQAction.values().length];
        Arrays.fill(ALL_ACTIONS_VALID, true);
    }

    private final QLearningSession session;
    private final SearchPhase phase;
    private final QLearningConfig config;
    private final TabularQController controller;
    private final Random diversifyRandom;
    private int stagnationCount;

    /**
     * 创建一个阶段专属的 Q 排样策略。
     *
     * @param session 当前端到端求解共享的 Q-learning 会话
     * @param phase 当前排样阶段；决定使用哪一张独立 Q 表
     */
    public QPackingPolicy(QLearningSession session, SearchPhase phase) {
        this.session = session;
        this.phase = phase;
        this.config = session.config();
        this.controller = session.controller(phase);
        this.diversifyRandom = new Random(config.seed() + 9_973L * (phase.ordinal() + 1L));
    }

    /**
     * 对当前 Beam 节点选择一个 Q 动作并返回按该动作排序后的可行放置候选。
     *
     * @param state 当前 Beam 搜索状态
     * @param childLimit 当前节点允许扩展的最大子节点数量
     * @return 包含状态、动作、候选和局部势函数的决策对象
     */
    public Decision select(State state, int childLimit) {
        int safeChildLimit = Math.max(1, childLimit);
        int stateKey = encodeState(state, stagnationCount);
        int actionIndex = controller.selectAction(stateKey, ALL_ACTIONS_VALID);
        PackingQAction action = PackingQAction.values()[actionIndex];
        List<Candidate> candidates = enumerateCandidates(state);
        List<Move> moves = rankCandidates(candidates, action, safeChildLimit);
        Space discardSpace = moves.isEmpty() ? selectDiscardSpace(state) : null;
        return new Decision(stateKey, actionIndex, action, moves, discardSpace,
                potential(state), candidates.size(), controller.getEpsilon());
    }

    /**
     * 根据当前动作产生的子状态更新 Q 值。
     *
     * @param decision 先前由 {@link #select(State, int)} 返回的决策
     * @param successorStates 当前动作生成的全部可行子状态；空列表表示没有可行放置
     */
    public void observe(Decision decision, List<State> successorStates) {
        session.recordDecision(phase, decision.stateKey(), decision.actionIndex());
        if (successorStates == null || successorStates.isEmpty()) {
            double reward = -0.50;
            controller.update(decision.stateKey(), decision.actionIndex(), reward,
                    decision.stateKey(), true, null);
            stagnationCount = Math.min(4, stagnationCount + 1);
            session.trace(phase, decision.stateKey(), decision.action().name(), decision.epsilon(),
                    reward, decision.stateKey(), true, decision.candidateCount());
            return;
        }

        State bestSuccessor = successorStates.get(0);
        double bestPotential = potential(bestSuccessor);
        for (int i = 1; i < successorStates.size(); i++) {
            double candidatePotential = potential(successorStates.get(i));
            if (candidatePotential > bestPotential) {
                bestPotential = candidatePotential;
                bestSuccessor = successorStates.get(i);
            }
        }

        double reward = clamp(bestPotential - decision.potential(), -0.25, 0.25);
        if (reward > 1e-9) {
            stagnationCount = 0;
        } else {
            stagnationCount = Math.min(4, stagnationCount + 1);
        }
        int nextStateKey = encodeState(bestSuccessor, stagnationCount);
        controller.update(decision.stateKey(), decision.actionIndex(), reward,
                nextStateKey, false, ALL_ACTIONS_VALID);
        session.trace(phase, decision.stateKey(), decision.action().name(), decision.epsilon(),
                reward, nextStateKey, false, decision.candidateCount());
    }

    /**
     * 枚举有限数量的可行“空闲区-块”组合，并预计算各动作共享的局部特征。
     *
     * @param state 当前 Beam Search 状态。
     * @return 已完成 regret 特征计算的候选列表。
     */
    private List<Candidate> enumerateCandidates(State state) {
        Instance instance = state.getInstance();
        List<Space> spaces = state.getCandidateSpaces(config.maxCandidateSpaces());
        List<Candidate> candidates = new ArrayList<>();
        for (Space space : spaces) {
            // packBlock 依赖 cornerId；对空间副本显式初始化该角点信息。
            space.cornerDistance(instance.length, instance.width);
            for (GeneralBlock block : state.getFeasibleBlocks(space, config.maxCandidateBlocksPerSpace())) {
                candidates.add(createCandidate(state, space, block));
            }
        }
        calculateRegrets(candidates);
        return candidates;
    }

    /**
     * 为一个可行放置组合计算面积浪费、边剩余、碎片数和接触长度特征。
     *
     * @param state 当前 Beam Search 状态。
     * @param space 候选放置所在的空闲区副本。
     * @param block 候选放置的矩形块。
     * @return 带有局部特征的内部候选对象。
     */
    private Candidate createCandidate(State state, Space space, GeneralBlock block) {
        Instance instance = state.getInstance();
        PlacedBlock placedBlock = space.packBlock(block);
        double areaWaste = Math.max(0.0, space.volume - block.blockVolume);
        int lengthLeft = Math.max(0, space.length() - block.length);
        int widthLeft = Math.max(0, space.width() - block.width);
        int shortLeft = Math.min(lengthLeft, widthLeft);
        int longLeft = Math.max(lengthLeft, widthLeft);
        int fragmentCount = (lengthLeft > 0 ? 1 : 0) + (widthLeft > 0 ? 1 : 0);
        double contactLength = estimateContactLength(placedBlock, state.getPlacedBlocks(), instance);
        return new Candidate(space, block, placedBlock.x, placedBlock.y, areaWaste,
                shortLeft, longLeft, fragmentCount, contactLength, 0.0);
    }

    /**
     * 按块类型计算第一、第二优放置成本的差，作为 REGRET_2 动作的排序特征。
     *
     * @param candidates 待原地更新 regret 特征的候选列表。
     */
    private void calculateRegrets(List<Candidate> candidates) {
        Map<Integer, double[]> bestTwoCosts = new HashMap<>();
        for (Candidate candidate : candidates) {
            int blockKey = candidate.block().component.length == 0 ? -1 : candidate.block().component[0];
            double cost = candidate.areaWaste() + candidate.shortLeft();
            double[] bestTwo = bestTwoCosts.computeIfAbsent(blockKey,
                    ignored -> new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY});
            if (cost < bestTwo[0]) {
                bestTwo[1] = bestTwo[0];
                bestTwo[0] = cost;
            } else if (cost < bestTwo[1]) {
                bestTwo[1] = cost;
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            int blockKey = candidate.block().component.length == 0 ? -1 : candidate.block().component[0];
            double[] bestTwo = bestTwoCosts.get(blockKey);
            double regret = bestTwo[1] == Double.POSITIVE_INFINITY
                    ? 0.0
                    : Math.max(0.0, bestTwo[1] - bestTwo[0]);
            candidates.set(i, candidate.withRegret(regret));
        }
    }

    /**
     * 使用 Q 动作指定的比较器排序候选，并去重后截取 Beam Search 所需的子节点数。
     *
     * @param candidates 已计算局部特征的可行候选。
     * @param action 当前 Q-learning 选择的排序动作。
     * @param childLimit 当前节点最多保留的子节点数量。
     * @return 去重且已排序的实际放置动作列表。
     */
    private List<Move> rankCandidates(List<Candidate> candidates,
                                      PackingQAction action,
                                      int childLimit) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort(comparatorFor(action));
        if (action == PackingQAction.DIVERSIFY) {
            int topCount = Math.max(1, (int) Math.ceil(ranked.size() * config.diversifyFraction()));
            List<Candidate> topCandidates = new ArrayList<>(ranked.subList(0, topCount));
            java.util.Collections.shuffle(topCandidates, diversifyRandom);
            ranked = new ArrayList<>(topCandidates);
            ranked.addAll(candidates.stream()
                    .filter(candidate -> !topCandidates.contains(candidate))
                    .sorted(comparatorFor(PackingQAction.BEST_AREA_FIT))
                    .toList());
        }

        List<Move> moves = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        for (Candidate candidate : ranked) {
            String signature = candidate.x() + ":" + candidate.y() + ":"
                    + candidate.block().length + ":" + candidate.block().width + ":"
                    + Arrays.toString(candidate.block().component);
            if (signatures.add(signature)) {
                moves.add(new Move(candidate.space(), candidate.block()));
            }
            if (moves.size() >= childLimit) {
                break;
            }
        }
        return moves;
    }

    /**
     * 返回一个 Q 动作对应的候选比较器。
     *
     * @param action 需要执行的候选排序动作。
     * @return 对应动作的稳定候选比较器。
     */
    private Comparator<Candidate> comparatorFor(PackingQAction action) {
        Comparator<Candidate> areaFit = Comparator
                .comparingDouble(Candidate::areaWaste)
                .thenComparingInt(Candidate::shortLeft)
                .thenComparingInt(Candidate::longLeft)
                .thenComparing(Comparator.comparingDouble(Candidate::contactLength).reversed());
        return switch (action) {
            case BEST_AREA_FIT -> areaFit;
            case BEST_SHORT_SIDE_FIT -> Comparator.comparingInt(Candidate::shortLeft)
                    .thenComparingDouble(Candidate::areaWaste)
                    .thenComparingInt(Candidate::longLeft);
            case MAX_CONTACT -> Comparator.comparingDouble(Candidate::contactLength).reversed()
                    .thenComparingDouble(Candidate::areaWaste)
                    .thenComparingInt(Candidate::shortLeft);
            case MIN_FRAGMENTATION -> Comparator.comparingInt(Candidate::fragmentCount)
                    .thenComparingDouble(Candidate::areaWaste)
                    .thenComparing(Comparator.comparingDouble(Candidate::contactLength).reversed());
            case SMALL_GAP_MATCH -> Comparator.comparingDouble((Candidate candidate) -> candidate.space().volume)
                    .thenComparingDouble(Candidate::areaWaste)
                    .thenComparingInt(Candidate::shortLeft);
            case REGRET_2 -> Comparator.comparingDouble(Candidate::regret).reversed()
                    .thenComparingDouble(Candidate::areaWaste)
                    .thenComparingInt(Candidate::shortLeft);
            case DIVERSIFY -> areaFit;
        };
    }

    /**
     * 在没有可放置块时选择需要丢弃的空闲区，保持原算法的递归推进语义。
     *
     * @param state 当前没有可行放置的搜索状态。
     * @return 按现有空间启发式排在首位的空闲区副本；没有空闲区时返回 {@code null}。
     */
    private Space selectDiscardSpace(State state) {
        List<Space> spaces = state.getCandidateSpaces(1);
        return spaces.isEmpty() ? null : spaces.get(0);
    }

    /**
     * 将连续特征离散为 3^5=243 个排样状态之一。
     *
     * @param state 当前 Beam Search 状态。
     * @param stagnation 连续未改善次数，用于区分搜索停滞程度。
     * @return 可作为 Q 表键的非负状态编号。
     */
    private int encodeState(State state, int stagnation) {
        Instance instance = state.getInstance();
        double boardArea = Math.max(1.0, (double) instance.length * instance.width);
        double remainingArea = 0.0;
        int[] freeBoxes = state.getFreeBoxes();
        for (int i = 0; i < Math.min(freeBoxes.length, instance.boxes.length); i++) {
            remainingArea += Math.max(0, freeBoxes[i]) * instance.boxes[i].volume;
        }
        int remainingBin = bucket(remainingArea / boardArea, 0.25, 0.60);
        int utilizationBin = bucket(state.getTotalPlacedBlockArea() / boardArea, 0.60, 0.82);
        int fragmentationBin = bucket(fragmentation(state), 0.25, 0.55);
        int hardBin = bucket(hardItemRatio(state), 0.15, 0.35);
        int stagnationBin = stagnation <= 0 ? 0 : (stagnation < 4 ? 1 : 2);
        return remainingBin + 3 * utilizationBin + 9 * fragmentationBin
                + 27 * hardBin + 81 * stagnationBin;
    }

    /**
     * 计算局部势函数，用于把一个 Beam 扩展转换为稠密 Q-learning 奖励。
     *
     * @param state 要评估的排样状态。
     * @return 越大表示矩形占用率更高且剩余空间更规整的势函数值。
     */
    private double potential(State state) {
        Instance instance = state.getInstance();
        double boardArea = Math.max(1.0, (double) instance.length * instance.width);
        double utilization = clamp(state.getTotalPlacedBlockArea() / boardArea, 0.0, 1.0);
        return utilization - 0.40 * fragmentation(state) - 0.30 * hardItemRatio(state);
    }

    /**
     * 以最大可用空闲区占总自由面积的比例估算空间碎片率。
     *
     * @param state 要评估的排样状态。
     * @return 位于 {@code [0, 1]} 的碎片率；越大表示空闲区越分散。
     */
    private double fragmentation(State state) {
        List<Space> spaces = state.getCandidateSpaces(config.maxCandidateSpaces());
        if (spaces.isEmpty()) {
            return 1.0;
        }
        double largestSpace = 0.0;
        for (Space space : spaces) {
            largestSpace = Math.max(largestSpace, space.volume);
        }
        Instance instance = state.getInstance();
        double boardArea = Math.max(1.0, (double) instance.length * instance.width);
        double freeArea = Math.max(1.0, boardArea - state.getTotalPlacedBlockArea());
        return clamp(1.0 - largestSpace / freeArea, 0.0, 1.0);
    }

    /**
     * 统计当前候选块中可放位置较少的难放块比例。
     *
     * @param state 要评估的排样状态。
     * @return 位于 {@code [0, 1]} 的难放块比例。
     */
    private double hardItemRatio(State state) {
        List<Space> spaces = state.getCandidateSpaces(config.maxCandidateSpaces());
        int sampleSize = Math.min(32, state.availableBlocks.length);
        if (sampleSize == 0) {
            return 0.0;
        }
        int hardCount = 0;
        for (int i = 0; i < sampleSize; i++) {
            GeneralBlock block = state.availableBlocks[i];
            int fitCount = 0;
            for (Space space : spaces) {
                if (block.length <= space.length() && block.width <= space.width()) {
                    fitCount++;
                }
            }
            if (fitCount <= config.hardFitThreshold()) {
                hardCount++;
            }
        }
        return (double) hardCount / sampleSize;
    }

    /**
     * 估算候选块与板材边界、既有块相邻时形成的总接触边长。
     *
     * @param candidate 候选块的放置坐标和尺寸。
     * @param placedBlocks 当前状态中已经放置的块。
     * @param instance 当前板材实例，用于判断候选是否贴边。
     * @return 候选块形成的接触长度。
     */
    private double estimateContactLength(PlacedBlock candidate,
                                         PlacedBlock[] placedBlocks,
                                         Instance instance) {
        int candidateRight = candidate.x + candidate.block.length;
        int candidateTop = candidate.y + candidate.block.width;
        double contact = 0.0;
        if (candidate.x == 0 || candidateRight == instance.length) {
            contact += candidate.block.width;
        }
        if (candidate.y == 0 || candidateTop == instance.width) {
            contact += candidate.block.length;
        }
        for (PlacedBlock placed : placedBlocks) {
            int placedRight = placed.x + placed.block.length;
            int placedTop = placed.y + placed.block.width;
            if (candidateRight == placed.x || placedRight == candidate.x) {
                contact += overlapLength(candidate.y, candidateTop, placed.y, placedTop);
            }
            if (candidateTop == placed.y || placedTop == candidate.y) {
                contact += overlapLength(candidate.x, candidateRight, placed.x, placedRight);
            }
        }
        return contact;
    }

    /**
     * 计算两段一维闭开区间的重叠长度。
     *
     * @param startA 第一段起点。
     * @param endA 第一段终点。
     * @param startB 第二段起点。
     * @param endB 第二段终点。
     * @return 两段区间的非负重叠长度。
     */
    private int overlapLength(int startA, int endA, int startB, int endB) {
        return Math.max(0, Math.min(endA, endB) - Math.max(startA, startB));
    }

    /**
     * 将连续特征划分为低、中、高三个离散桶。
     *
     * @param value 待离散化的连续特征值。
     * @param lowerThreshold 低桶与中桶之间的阈值。
     * @param upperThreshold 中桶与高桶之间的阈值。
     * @return 0、1 或 2，分别对应低、中、高桶。
     */
    private int bucket(double value, double lowerThreshold, double upperThreshold) {
        if (!Double.isFinite(value) || value <= lowerThreshold) {
            return 0;
        }
        return value <= upperThreshold ? 1 : 2;
    }

    /**
     * 将数值截断到给定闭区间。
     *
     * @param value 待截断数值。
     * @param lower 区间下界。
     * @param upper 区间上界。
     * @return 位于 {@code [lower, upper]} 的结果。
     */
    private double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    /**
     * Q 动作选出的一个实际放置候选。
     *
     * @param space 放置所使用的剩余空间副本
     * @param block 将要放置的矩形块
     */
    public record Move(Space space, GeneralBlock block) {
    }

    /**
     * 一次 Q 动作决策及其后续更新所需上下文。
     *
     * @param stateKey 当前离散状态编号
     * @param actionIndex 动作数组索引
     * @param action 当前选择的动作
     * @param moves 按动作排序后保留的可行放置候选
     * @param discardSpace 无可行候选时应删除的剩余空间
     * @param potential 动作执行前的局部势函数值
     * @param candidateCount 动作评分前的可行候选总数
     * @param epsilon 当前动作选择时的探索率
     */
    public record Decision(int stateKey,
                           int actionIndex,
                           PackingQAction action,
                           List<Move> moves,
                           Space discardSpace,
                           double potential,
                           int candidateCount,
                           double epsilon) {
    }

    private record Candidate(Space space,
                             GeneralBlock block,
                             int x,
                             int y,
                             double areaWaste,
                             int shortLeft,
                             int longLeft,
                             int fragmentCount,
                             double contactLength,
                             double regret) {
        private Candidate withRegret(double value) {
            return new Candidate(space, block, x, y, areaWaste, shortLeft, longLeft,
                    fragmentCount, contactLength, value);
        }
    }
}
