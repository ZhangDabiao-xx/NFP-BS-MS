package org.example.qlearning;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

/**
 * 使用离散状态和有限动作集的表格型 Q-learning 控制器。
 *
 * <p>Q 表按 {@code stateKey -> double[actionCount]} 保存。状态编码由阶段专属
 * encoder 完成，控制器只负责 ε-greedy 选动作和标准 Q 值更新。</p>
 */
public final class TabularQController {

    private final int actionCount;
    private final QLearningConfig config;
    private final Random random;
    private final Map<Integer, double[]> qTable = new LinkedHashMap<>();
    private final Map<Integer, int[]> visitCounts = new LinkedHashMap<>();
    private int decisionCount;
    private double epsilon;

    /**
     * 创建一个控制器。
     *
     * @param actionCount 动作总数；动作索引必须位于 {@code [0, actionCount)}
     * @param config 控制器的学习、探索和随机化配置
     * @param randomSeed 当前控制器专用随机种子
     */
    public TabularQController(int actionCount, QLearningConfig config, long randomSeed) {
        if (actionCount <= 0) {
            throw new IllegalArgumentException("actionCount 必须为正数: " + actionCount);
        }
        this.actionCount = actionCount;
        this.config = config;
        this.random = new Random(randomSeed);
        this.epsilon = config.epsilonInitial();
    }

    /**
     * 依据 ε-greedy 策略从合法动作中选择一个动作。
     *
     * @param stateKey 已离散化的当前状态编号
     * @param validActions 动作合法掩码；数组长度必须等于动作总数，至少一个元素为 {@code true}
     * @return 被选择动作的数组索引
     */
    public int selectAction(int stateKey, boolean[] validActions) {
        validateMask(validActions);
        boolean explore = config.mode() == QMode.TRAIN
                && (decisionCount < config.warmupDecisions() || random.nextDouble() < epsilon);
        int selectedAction = explore
                ? randomValidAction(validActions)
                : bestAction(stateKey, validActions);

        decisionCount++;
        if (config.mode() == QMode.TRAIN && decisionCount > config.warmupDecisions()) {
            epsilon = Math.max(config.epsilonMinimum(), epsilon * config.epsilonDecay());
        }
        return selectedAction;
    }

    /**
     * 用一次状态转移更新 Q 值。
     *
     * @param stateKey 动作执行前的离散状态
     * @param actionIndex 已执行动作的索引
     * @param reward 当前动作得到的奖励，建议归一化到 {@code [-1, 1]}
     * @param nextStateKey 执行动作后的离散状态；终止状态可传任意整数
     * @param terminal 是否为终止状态；终止时不叠加未来 Q 值
     * @param validNextActions 下一状态的合法动作掩码；终止状态可传 {@code null}
     */
    public void update(int stateKey,
                       int actionIndex,
                       double reward,
                       int nextStateKey,
                       boolean terminal,
                       boolean[] validNextActions) {
        if (config.mode() != QMode.TRAIN) {
            return;
        }
        if (actionIndex < 0 || actionIndex >= actionCount) {
            throw new IllegalArgumentException("动作索引越界: " + actionIndex);
        }
        if (!Double.isFinite(reward)) {
            return;
        }

        double[] currentQValues = qValues(stateKey);
        double futureValue = 0.0;
        if (!terminal) {
            validateMask(validNextActions);
            futureValue = qValues(nextStateKey)[bestAction(nextStateKey, validNextActions)];
        }

        double target = reward + config.gamma() * futureValue;
        currentQValues[actionIndex] += config.alpha() * (target - currentQValues[actionIndex]);
        visitCounts.computeIfAbsent(stateKey, ignored -> new int[actionCount])[actionIndex]++;
    }

    /**
     * 使用完整案例的终局奖励对一次已执行动作进行额外强化。
     *
     * <p>该更新不估计下一状态价值，而是把案例最终的板材数量、利用率和时间质量
     * 直接作为终止目标。{@code eligibility} 用于让离案例末尾更近的动作获得更大的
     * 学习步长，从而避免一次长搜索被同一终局奖励过度覆盖。</p>
     *
     * @param stateKey 执行动作时的离散状态编号。
     * @param actionIndex 已执行动作在 Q 表中的索引。
     * @param terminalReward 由完整案例结果计算出的归一化终局奖励。
     * @param eligibility 当前动作获得终局强化的资格权重，建议位于 {@code [0, 1]}。
     */
    public void reinforceTerminal(int stateKey,
                                  int actionIndex,
                                  double terminalReward,
                                  double eligibility) {
        if (config.mode() != QMode.TRAIN || !Double.isFinite(terminalReward)) {
            return;
        }
        if (actionIndex < 0 || actionIndex >= actionCount) {
            throw new IllegalArgumentException("动作索引越界: " + actionIndex);
        }
        double effectiveEligibility = clamp(eligibility, 0.0, 1.0);
        if (effectiveEligibility <= 0.0) {
            return;
        }
        double[] currentQValues = qValues(stateKey);
        currentQValues[actionIndex] += config.alpha() * effectiveEligibility
                * (terminalReward - currentQValues[actionIndex]);
        visitCounts.computeIfAbsent(stateKey, ignored -> new int[actionCount])[actionIndex]++;
    }

    /**
     * 返回当前状态指定动作的 Q 值；未访问状态的 Q 值为零。
     *
     * @param stateKey 已离散化的状态编号
     * @param actionIndex 要查询的动作索引
     * @return 当前 Q 值
     */
    public double getQValue(int stateKey, int actionIndex) {
        if (actionIndex < 0 || actionIndex >= actionCount) {
            throw new IllegalArgumentException("动作索引越界: " + actionIndex);
        }
        return qValues(stateKey)[actionIndex];
    }

    /**
     * 将当前 Q 表和访问次数写入 Properties 对象。
     *
     * @param properties 用于保存当前控制器数据的 Properties 对象
     * @param prefix 当前控制器的键前缀，用于区分不同搜索阶段
     */
    public void saveTo(Properties properties, String prefix) {
        properties.setProperty(prefix + ".actionCount", String.valueOf(actionCount));
        properties.setProperty(prefix + ".epsilon", String.valueOf(epsilon));
        properties.setProperty(prefix + ".decisionCount", String.valueOf(decisionCount));
        for (Map.Entry<Integer, double[]> entry : qTable.entrySet()) {
            properties.setProperty(prefix + ".q." + entry.getKey(), join(entry.getValue()));
        }
        for (Map.Entry<Integer, int[]> entry : visitCounts.entrySet()) {
            properties.setProperty(prefix + ".visits." + entry.getKey(), join(entry.getValue()));
        }
    }

    /**
     * 从 Properties 对象加载当前控制器数据；动作数量不匹配时忽略旧表，避免动作语义变化后误用数据。
     *
     * @param properties 包含已保存 Q 表的 Properties 对象
     * @param prefix 当前控制器的数据键前缀
     */
    public void loadFrom(Properties properties, String prefix) {
        String savedActionCount = properties.getProperty(prefix + ".actionCount");
        if (savedActionCount == null || parseInt(savedActionCount, -1) != actionCount) {
            return;
        }

        epsilon = clamp(parseDouble(properties.getProperty(prefix + ".epsilon"), config.epsilonInitial()),
                config.epsilonMinimum(), config.epsilonInitial());
        decisionCount = Math.max(0, parseInt(properties.getProperty(prefix + ".decisionCount"), 0));

        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix + ".q.")) {
                Integer stateKey = parseStateKey(key, prefix + ".q.");
                double[] values = parseDoubleArray(properties.getProperty(key));
                if (stateKey != null && values != null) {
                    qTable.put(stateKey, values);
                }
            } else if (key.startsWith(prefix + ".visits.")) {
                Integer stateKey = parseStateKey(key, prefix + ".visits.");
                int[] values = parseIntArray(properties.getProperty(key));
                if (stateKey != null && values != null) {
                    visitCounts.put(stateKey, values);
                }
            }
        }
    }

    /** @return 已累计的动作决策次数。 */
    public int getDecisionCount() {
        return decisionCount;
    }

    /** @return 当前 ε-greedy 探索概率。 */
    public double getEpsilon() {
        return epsilon;
    }

    private double[] qValues(int stateKey) {
        return qTable.computeIfAbsent(stateKey, ignored -> new double[actionCount]);
    }

    private int bestAction(int stateKey, boolean[] validActions) {
        double[] values = qValues(stateKey);
        int best = -1;
        double bestValue = Double.NEGATIVE_INFINITY;
        int tieCount = 0;
        for (int action = 0; action < actionCount; action++) {
            if (!validActions[action]) {
                continue;
            }
            double value = values[action];
            if (value > bestValue + 1e-12) {
                bestValue = value;
                best = action;
                tieCount = 1;
            } else if (Math.abs(value - bestValue) <= 1e-12) {
                tieCount++;
                if (random.nextInt(tieCount) == 0) {
                    best = action;
                }
            }
        }
        return best;
    }

    private int randomValidAction(boolean[] validActions) {
        int validCount = 0;
        for (boolean validAction : validActions) {
            if (validAction) {
                validCount++;
            }
        }
        int target = random.nextInt(validCount);
        for (int action = 0; action < actionCount; action++) {
            if (validActions[action] && target-- == 0) {
                return action;
            }
        }
        throw new IllegalStateException("不存在合法动作");
    }

    private void validateMask(boolean[] validActions) {
        if (validActions == null || validActions.length != actionCount) {
            throw new IllegalArgumentException("合法动作掩码长度必须为 " + actionCount);
        }
        for (boolean validAction : validActions) {
            if (validAction) {
                return;
            }
        }
        throw new IllegalArgumentException("至少需要一个合法动作");
    }

    private String join(double[] values) {
        return Arrays.toString(values).replace("[", "").replace("]", "").replace(" ", "");
    }

    private String join(int[] values) {
        return Arrays.toString(values).replace("[", "").replace("]", "").replace(" ", "");
    }

    private double[] parseDoubleArray(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != actionCount) {
            return null;
        }
        double[] values = new double[actionCount];
        for (int i = 0; i < parts.length; i++) {
            values[i] = parseDouble(parts[i], 0.0);
        }
        return values;
    }

    private int[] parseIntArray(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != actionCount) {
            return null;
        }
        int[] values = new int[actionCount];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Math.max(0, parseInt(parts[i], 0));
        }
        return values;
    }

    private Integer parseStateKey(String key, String prefix) {
        try {
            return Integer.parseInt(key.substring(prefix.length()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private double parseDouble(String raw, double fallback) {
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }
}
