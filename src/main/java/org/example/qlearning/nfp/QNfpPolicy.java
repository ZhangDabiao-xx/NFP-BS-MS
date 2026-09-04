package org.example.qlearning.nfp;

import org.example.nfp.Block;
import org.example.qlearning.QLearningConfig;
import org.example.qlearning.QLearningSession;
import org.example.qlearning.SearchPhase;
import org.example.qlearning.TabularQController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 用 Q-learning 控制 NFP 根 Beam 中已通过几何校验的候选排序。
 *
 * <p>本类的输入只能是 {@code tryAddItems()} 已经创建成功的 {@link Block}。因此它
 * 不会改变 NFP 外轮廓生成、旋转约束、正面积重叠检查、连通性检查和板材尺寸检查；
 * 它只决定这些合法候选进入有限 Beam 名额时的优先顺序。</p>
 */
public final class QNfpPolicy {

    private static final boolean[] ALL_ACTIONS_VALID;

    static {
        ALL_ACTIONS_VALID = new boolean[NfpQAction.values().length];
        Arrays.fill(ALL_ACTIONS_VALID, true);
    }

    private final QLearningSession session;
    private final QLearningConfig config;
    private final TabularQController controller;

    /**
     * 创建 NFP 拼接候选的 Q-learning 排序策略。
     *
     * @param session 当前端到端求解共享的 Q-learning 会话。
     */
    public QNfpPolicy(QLearningSession session) {
        this.session = session;
        this.config = session.config();
        this.controller = session.controller(SearchPhase.NFP_STITCH);
    }

    /**
     * 为一层根 Beam 的合法拼接候选选择排序动作。
     *
     * @param candidates 已通过 NFP 几何与业务约束检查的候选块。
     * @param beamWidth 当前根 Beam 允许保留的状态数量。
     * @return 包含动作、状态和候选基准势函数的决策上下文。
     */
    public Decision select(List<Block> candidates, int beamWidth) {
        int stateKey = encodeState(candidates, beamWidth);
        int actionIndex = controller.selectAction(stateKey, ALL_ACTIONS_VALID);
        NfpQAction action = NfpQAction.values()[actionIndex];
        return new Decision(
                stateKey,
                actionIndex,
                action,
                comparatorFor(action),
                averagePotential(candidates),
                candidates == null ? 0 : candidates.size(),
                controller.getEpsilon(),
                Math.max(1, beamWidth));
    }

    /**
     * 根据经动作排序后实际保留的 Beam 状态更新 Q 值。
     *
     * @param decision 先前由 {@link #select(List, int)} 创建的决策上下文。
     * @param selectedCandidates 当前动作实际保留、将进入下一层 NFP 搜索的候选块。
     */
    public void observe(Decision decision, List<Block> selectedCandidates) {
        if (selectedCandidates == null || selectedCandidates.isEmpty()) {
            double reward = -0.50;
            controller.update(decision.stateKey(), decision.actionIndex(), reward,
                    decision.stateKey(), true, null);
            session.trace(SearchPhase.NFP_STITCH, decision.stateKey(), decision.action().name(),
                    decision.epsilon(), reward, decision.stateKey(), true, decision.candidateCount());
            return;
        }

        double selectedPotential = averagePotential(selectedCandidates);
        double reward = clamp(selectedPotential - decision.baselinePotential(), -0.25, 0.25);
        int nextStateKey = encodeState(selectedCandidates, decision.beamWidth());
        controller.update(decision.stateKey(), decision.actionIndex(), reward,
                nextStateKey, false, ALL_ACTIONS_VALID);
        session.trace(SearchPhase.NFP_STITCH, decision.stateKey(), decision.action().name(),
                decision.epsilon(), reward, nextStateKey, false, decision.candidateCount());
    }

    /**
     * 返回指定 NFP 动作对应的稳定比较器。
     *
     * @param action 当前 Q-learning 选定的排序动作。
     * @return 越靠前越应优先进入根 Beam 的比较器。
     */
    private Comparator<Block> comparatorFor(NfpQAction action) {
        Comparator<Block> combinedScore = Comparator
                .comparingDouble(Block::lastStitchScore).reversed()
                .thenComparing(Comparator.comparingInt(this::cavityInsertionCount).reversed())
                .thenComparing(Comparator.comparingDouble((Block block) -> block.fillRate).reversed())
                .thenComparing(Comparator.comparingInt(Block::memberCount).reversed())
                .thenComparing(Comparator.comparingDouble((Block block) -> block.score2).reversed())
                .thenComparing(block -> block.id);
        return switch (action) {
            case COMBINED_SCORE -> combinedScore;
            case CAVITY_INSERTION -> Comparator.comparingInt(this::cavityInsertionCount).reversed()
                    .thenComparing(combinedScore);
            case FILL_RATE -> Comparator.comparingDouble((Block block) -> block.fillRate).reversed()
                    .thenComparing(combinedScore);
            case COMPACT_BOUNDING_BOX -> Comparator.comparingDouble((Block block) -> block.boxArea)
                    .thenComparing(Comparator.comparingDouble((Block block) -> block.fillRate).reversed())
                    .thenComparing(combinedScore);
            case MEMBER_EXPANSION -> Comparator.comparingInt(Block::memberCount).reversed()
                    .thenComparing(combinedScore);
            case BALANCED -> Comparator.comparingDouble(this::balancedScore).reversed()
                    .thenComparing(combinedScore);
        };
    }

    /**
     * 将一层候选集的结构离散为 3^5=243 个 NFP 搜索状态之一。
     *
     * @param candidates 已通过几何校验的候选块。
     * @param beamWidth 当前根 Beam 宽度。
     * @return 可作为 NFP Q 表键的非负状态编号。
     */
    private int encodeState(List<Block> candidates, int beamWidth) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        double maxFillRate = 0.0;
        int maxMemberCount = 0;
        int cavityCount = 0;
        int pendingOuterCount = 0;
        for (Block block : candidates) {
            maxFillRate = Math.max(maxFillRate, block.fillRate);
            maxMemberCount = Math.max(maxMemberCount, block.memberCount());
            if (cavityInsertionCount(block) > 0) {
                cavityCount++;
            }
            if (isLargeOuterPending(block)) {
                pendingOuterCount++;
            }
        }
        int depthBin = maxMemberCount <= 2 ? 0 : (maxMemberCount <= 4 ? 1 : 2);
        int fillBin = bucket(maxFillRate, 0.75, 0.90);
        int cavityBin = bucket((double) cavityCount / candidates.size(), 0.15, 0.45);
        int breadthBin = bucket((double) candidates.size() / Math.max(1, beamWidth), 2.0, 5.0);
        int pendingBin = bucket((double) pendingOuterCount / candidates.size(), 0.15, 0.45);
        return depthBin + 3 * fillBin + 9 * cavityBin + 27 * breadthBin + 81 * pendingBin;
    }

    /**
     * 计算一个候选集合的平均局部势函数，作为 Q-learning 的稠密奖励基准。
     *
     * @param candidates 要评估的合法 NFP 候选块。
     * @return 越大代表凹腔利用、填充率、紧凑性和组合进度更好的平均势函数值。
     */
    private double averagePotential(List<Block> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return -1.0;
        }
        double total = 0.0;
        for (Block candidate : candidates) {
            total += potential(candidate);
        }
        return total / candidates.size();
    }

    /**
     * 计算单个合法拼接候选的归一化局部势函数。
     *
     * @param block 已通过几何校验的 NFP 组合块。
     * @return 用于奖励比较的局部势函数值。
     */
    private double potential(Block block) {
        double compactness = block.boxArea <= 0.0
                ? 0.0
                : clamp(block.areaSum / block.boxArea, 0.0, 1.0);
        double memberProgress = clamp(block.memberCount() / (double) Block.MAX_MEMBER_COUNT, 0.0, 1.0);
        double cavityBonus = clamp(cavityInsertionCount(block) / 3.0, 0.0, 1.0);
        return 0.45 * compactness + 0.25 * cavityBonus + 0.20 * memberProgress
                + 0.10 * normalizedScore(block.lastStitchScore());
    }

    /**
     * 计算 BALANCED 动作使用的可解释综合排序分数。
     *
     * @param block 已通过几何校验的 NFP 组合块。
     * @return 越大表示综合结构质量越好。
     */
    private double balancedScore(Block block) {
        double compactness = block.boxArea <= 0.0 ? 0.0 : clamp(block.areaSum / block.boxArea, 0.0, 1.0);
        return 0.40 * compactness
                + 0.25 * clamp(cavityInsertionCount(block) / 3.0, 0.0, 1.0)
                + 0.20 * clamp(block.memberCount() / (double) Block.MAX_MEMBER_COUNT, 0.0, 1.0)
                + 0.15 * normalizedScore(block.lastStitchScore());
    }

    /**
     * 统计组合块中真实发生的凹腔插入次数。
     *
     * @param block 要检查的组合块。
     * @return 除根工件外进入已有外接框内部的工件数量。
     */
    private int cavityInsertionCount(Block block) {
        int count = 0;
        for (int index = 1; index < block.placements.size(); index++) {
            if (block.placements.get(index).candidateCavityInsertion) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断候选是否为仍应保留搜索名额的低填充大件外扩中间状态。
     *
     * @param block 已通过几何校验的候选块。
     * @return 若候选尚可能继续扩展形成高质量组合块则返回 {@code true}。
     */
    private boolean isLargeOuterPending(Block block) {
        if (block.memberCount() < 2 || block.fillRate >= 0.90) {
            return false;
        }
        for (int index = 1; index < block.placements.size(); index++) {
            Block.ItemPlacement placement = block.placements.get(index);
            if (!placement.item.smallItem && !placement.candidateCavityInsertion) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将连续特征划分为低、中、高三个离散桶。
     *
     * @param value 待离散化的连续特征。
     * @param lowerThreshold 低桶与中桶的分界阈值。
     * @param upperThreshold 中桶与高桶的分界阈值。
     * @return 0、1 或 2，分别表示低、中、高桶。
     */
    private int bucket(double value, double lowerThreshold, double upperThreshold) {
        if (!Double.isFinite(value) || value <= lowerThreshold) {
            return 0;
        }
        return value <= upperThreshold ? 1 : 2;
    }

    /**
     * 对 NFP 综合评分进行有界缩放，避免不同案例的绝对面积量级主导势函数。
     *
     * @param score 最近一次 NFP 拼接的综合评分。
     * @return 位于 {@code [-1, 1]} 的缩放评分。
     */
    private double normalizedScore(double score) {
        if (!Double.isFinite(score)) {
            return 0.0;
        }
        return score / (1.0 + Math.abs(score));
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
     * 一次 NFP Q 动作的上下文。
     *
     * @param stateKey 当前离散状态编号。
     * @param actionIndex 当前动作在 Q 表中的索引。
     * @param action 当前选择的 NFP 候选排序动作。
     * @param comparator 当前动作对应的候选比较器。
     * @param baselinePotential 排序前全部合法候选的平均势函数。
     * @param candidateCount 当前参与排序的合法候选数量。
     * @param epsilon 当前动作选择时的探索概率。
     * @param beamWidth 当前根 Beam 宽度。
     */
    public record Decision(int stateKey,
                           int actionIndex,
                           NfpQAction action,
                           Comparator<Block> comparator,
                           double baselinePotential,
                           int candidateCount,
                           double epsilon,
                           int beamWidth) {
    }
}
