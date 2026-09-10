package org.example.qlearning;

import java.util.Locale;

/**
 * 表格型 Q-learning 的运行参数。
 *
 * <p>参数可用 JVM 系统属性覆盖，例如
 * {@code -Dqlearning.mode=train -Dqlearning.seed=20260904}。
 * Q 表存储目录由统一入口的 {@code -Dqlearning.modelDirectory=<path>} 控制，
 * 默认使用 {@code data/qlearningModel}，不依赖排样结果目录。
 * 主程序仍只需要案例路径，不增加命令行位置参数。</p>
 */
public final class QLearningConfig {

    private final QMode mode;
    private final long seed;
    private final double alpha;
    private final double gamma;
    private final double epsilonInitial;
    private final double epsilonMinimum;
    private final double epsilonDecay;
    private final int warmupDecisions;
    private final int maxCandidateSpaces;
    private final int maxCandidateBlocksPerSpace;
    private final int fillCandidateSpaceScanLimit;
    private final boolean limitCandidateSet;
    private final int hardFitThreshold;
    private final double diversifyFraction;
    private final boolean traceEnabled;

    /**
     * 创建一组不可变的 Q-learning 参数。
     *
     * @param mode 控制器模式；OFF 保持原启发式，TRAIN 更新 Q 表，EVALUATE 只读取 Q 表
     * @param seed 随机种子；相同输入与种子应产生可复现实验轨迹
     * @param alpha Q 值学习率，建议位于 0 到 1 之间
     * @param gamma 折扣因子，建议位于 0 到 1 之间
     * @param epsilonInitial 初始探索概率
     * @param epsilonMinimum 最小探索概率
     * @param epsilonDecay 每次 Q 决策后的探索率衰减系数
     * @param warmupDecisions 训练开始时强制随机探索的决策次数
     * @param maxCandidateSpaces 每个 Beam 节点用于 Q 排序的最大剩余空间数
     * @param maxCandidateBlocksPerSpace 每个剩余空间用于 Q 排序的最大矩形候选数
     * @param fillCandidateSpaceScanLimit Sp 填充策略为寻找可行空间最多扫描的极大空间数
     * @param limitCandidateSet 是否限制 Q 策略可排序的候选集合；关闭时 Q 只改变排序而不删减候选
     * @param hardFitThreshold 可放位置数不高于此值时视为难放工件
     * @param diversifyFraction DIVERSIFY 动作可随机抽取的 Top 候选比例
     * @param traceEnabled 是否写出逐步 Q 决策轨迹
     */
    public QLearningConfig(QMode mode,
                           long seed,
                           double alpha,
                           double gamma,
                           double epsilonInitial,
                           double epsilonMinimum,
                           double epsilonDecay,
                           int warmupDecisions,
                           int maxCandidateSpaces,
                           int maxCandidateBlocksPerSpace,
                           int fillCandidateSpaceScanLimit,
                           boolean limitCandidateSet,
                           int hardFitThreshold,
                           double diversifyFraction,
                           boolean traceEnabled) {
        this.mode = mode == null ? QMode.OFF : mode;
        this.seed = seed;
        this.alpha = clamp(alpha, 0.0, 1.0);
        this.gamma = clamp(gamma, 0.0, 1.0);
        this.epsilonInitial = clamp(epsilonInitial, 0.0, 1.0);
        this.epsilonMinimum = clamp(Math.min(epsilonMinimum, epsilonInitial), 0.0, 1.0);
        this.epsilonDecay = clamp(epsilonDecay, 0.0, 1.0);
        this.warmupDecisions = Math.max(0, warmupDecisions);
        this.maxCandidateSpaces = Math.max(1, maxCandidateSpaces);
        this.maxCandidateBlocksPerSpace = Math.max(1, maxCandidateBlocksPerSpace);
        this.fillCandidateSpaceScanLimit = Math.max(this.maxCandidateSpaces, fillCandidateSpaceScanLimit);
        this.limitCandidateSet = limitCandidateSet;
        this.hardFitThreshold = Math.max(0, hardFitThreshold);
        this.diversifyFraction = clamp(diversifyFraction, 0.01, 1.0);
        this.traceEnabled = traceEnabled;
    }

    /**
     * 从 JVM 系统属性读取配置；未传属性时默认关闭 Q-learning，保持现有基线行为。
     *
     * @return 已解析的 Q-learning 配置
     */
    public static QLearningConfig fromSystemProperties() {
        return new QLearningConfig(
                parseMode(System.getProperty("qlearning.mode", "off")),
                parseLong("qlearning.seed", 20260904L),
                parseDouble("qlearning.alpha", 0.20),
                parseDouble("qlearning.gamma", 0.85),
                parseDouble("qlearning.epsilonInitial", 1.00),
                parseDouble("qlearning.epsilonMinimum", 0.05),
                parseDouble("qlearning.epsilonDecay", 0.995),
                parseInt("qlearning.warmupDecisions", 200),
                parseInt("qlearning.maxCandidateSpaces", 8),
                parseInt("qlearning.maxCandidateBlocksPerSpace", 12),
                parseInt("qlearning.fillCandidateSpaceScanLimit", 24),
                Boolean.parseBoolean(System.getProperty("qlearning.limitCandidateSet", "false")),
                parseInt("qlearning.hardFitThreshold", 2),
                parseDouble("qlearning.diversifyFraction", 0.15),
                Boolean.parseBoolean(System.getProperty("qlearning.trace", "false")));
    }

    /** @return 当前 Q-learning 运行模式。 */
    public QMode mode() {
        return mode;
    }

    /** @return 控制随机探索和多样化选择的随机种子。 */
    public long seed() {
        return seed;
    }

    /** @return Q 值学习率。 */
    public double alpha() {
        return alpha;
    }

    /** @return Q 值未来回报折扣因子。 */
    public double gamma() {
        return gamma;
    }

    /** @return 初始 ε-greedy 探索概率。 */
    public double epsilonInitial() {
        return epsilonInitial;
    }

    /** @return ε-greedy 探索概率下限。 */
    public double epsilonMinimum() {
        return epsilonMinimum;
    }

    /** @return 每次决策后的 ε 衰减系数。 */
    public double epsilonDecay() {
        return epsilonDecay;
    }

    /** @return 强制随机探索的前置决策次数。 */
    public int warmupDecisions() {
        return warmupDecisions;
    }

    /** @return 每个 Beam 节点参与 Q 排序的最大空间数。 */
    public int maxCandidateSpaces() {
        return maxCandidateSpaces;
    }

    /** @return 每个空间参与 Q 排序的最大矩形候选数。 */
    public int maxCandidateBlocksPerSpace() {
        return maxCandidateBlocksPerSpace;
    }

    /**
     * 返回 Sp 填充策略为定位可行空间而最多扫描的极大空闲空间数量。
     *
     * @return 扫描上限；至少不小于 {@link #maxCandidateSpaces()}。
     */
    public int fillCandidateSpaceScanLimit() {
        return fillCandidateSpaceScanLimit;
    }

    /**
     * 判断 Q-learning 是否为了降低计算量而限制可排序的候选空间和候选块。
     *
     * <p>默认 {@code false}：Q-learning 只改变已有可行候选的排序，不额外丢弃
     * 基线算法可访问的候选；设为 {@code true} 时，才使用各候选数量上限加速。</p>
     *
     * @return 需要限制候选集合时返回 {@code true}。
     */
    public boolean limitCandidateSet() {
        return limitCandidateSet;
    }

    /** @return 判定难放工件时允许的最大可放位置数。 */
    public int hardFitThreshold() {
        return hardFitThreshold;
    }

    /** @return 多样化动作抽取的 Top 候选比例。 */
    public double diversifyFraction() {
        return diversifyFraction;
    }

    /** @return 是否记录逐步 Q 决策轨迹。 */
    public boolean traceEnabled() {
        return traceEnabled;
    }

    /**
     * 在保持其余学习参数不变的前提下，创建使用指定运行模式的新配置。
     *
     * @param newMode 新的 Q-learning 运行模式。
     * @return 除运行模式外与当前配置完全相同的新配置。
     */
    public QLearningConfig withMode(QMode newMode) {
        return new QLearningConfig(
                newMode,
                seed,
                alpha,
                gamma,
                epsilonInitial,
                epsilonMinimum,
                epsilonDecay,
                warmupDecisions,
                maxCandidateSpaces,
                maxCandidateBlocksPerSpace,
                fillCandidateSpaceScanLimit,
                limitCandidateSet,
                hardFitThreshold,
                diversifyFraction,
                traceEnabled);
    }

    private static QMode parseMode(String raw) {
        try {
            return QMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return QMode.OFF;
        }
    }

    private static int parseInt(String key, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseLong(String key, long fallback) {
        try {
            return Long.parseLong(System.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double clamp(double value, double lower, double upper) {
        if (!Double.isFinite(value)) {
            return lower;
        }
        return Math.max(lower, Math.min(upper, value));
    }
}
