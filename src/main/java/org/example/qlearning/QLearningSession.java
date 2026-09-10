package org.example.qlearning;

import org.example.beamsearch.common.ExecutionResult;
import org.example.beamsearch.common.PlacedCuboid;
import org.example.beamsearch.common.Solution;
import org.example.beamsearch.application.PackingRuntimeConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 一次端到端求解期间共享的 Q-learning 会话。
 *
 * <p>会话为 NFP 拼接、优先件、填充和普通件阶段维护独立控制器，并在 TRAIN
 * 模式结束时保存 Q 表。OFF 模式不会创建文件，也不会改变原启发式流程。</p>
 */
public final class QLearningSession implements AutoCloseable {

    private static final String TABLE_FILE_NAME = "packing-q-tables.properties";
    private static final String TRACE_FILE_NAME = "packing-q-trace.csv";
    private static final int PACKING_ACTION_COUNT = 7;
    private static final int NFP_ACTION_COUNT = 6;
    private static final int FILL_MODE_ACTION_COUNT = 3;
    private static final int FILL_SPACE_ACTION_COUNT = 2;
    private static final int FILL_ITEM_ACTION_COUNT = 3;
    /** 单个案例最多保留的终局强化动作数，防止超大案例占用无界内存。 */
    private static final int MAX_TERMINAL_DECISIONS_PER_EPISODE = 20_000;

    private final QLearningConfig config;
    private final Path storageDirectory;
    private final Map<SearchPhase, TabularQController> controllers;
    private final QTraceWriter traceWriter;
    private final Map<String, List<DecisionReference>> episodeDecisions = new HashMap<>();
    private String activeEpisodeId;

    private QLearningSession(QLearningConfig config,
                             Path storageDirectory,
                             Map<SearchPhase, TabularQController> controllers,
                             QTraceWriter traceWriter) {
        this.config = config;
        this.storageDirectory = storageDirectory;
        this.controllers = controllers;
        this.traceWriter = traceWriter;
    }

    /**
     * 打开一个 Q-learning 会话，并在需要时读取既有 Q 表。
     *
     * @param config 当前运行的 Q-learning 配置
     * @param storageDirectory Q 表和轨迹文件目录；OFF 模式下可为 {@code null}
     * @return 可传递给排样阶段的共享 Q-learning 会话
     * @throws IOException 当 TRAIN/EVALUATE 模式的表文件无法读写时抛出
     */
    public static QLearningSession open(QLearningConfig config,
                                        Path storageDirectory) throws IOException {
        QLearningConfig effectiveConfig = config == null
                ? QLearningConfig.fromSystemProperties()
                : config;
        Map<SearchPhase, TabularQController> controllers = new EnumMap<>(SearchPhase.class);
        for (SearchPhase phase : SearchPhase.values()) {
            controllers.put(phase, new TabularQController(
                    actionCountFor(phase),
                    effectiveConfig,
                    effectiveConfig.seed() + 1_000_003L * (phase.ordinal() + 1L)));
        }

        if (effectiveConfig.mode() != QMode.OFF) {
            Files.createDirectories(storageDirectory);
            loadControllers(storageDirectory.resolve(TABLE_FILE_NAME), controllers);
        }

        QTraceWriter traceWriter = effectiveConfig.mode() == QMode.TRAIN && effectiveConfig.traceEnabled()
                ? new QTraceWriter(storageDirectory.resolve(TRACE_FILE_NAME))
                : null;
        return new QLearningSession(effectiveConfig, storageDirectory, controllers, traceWriter);
    }

    /**
     * 创建关闭 Q-learning 的会话，用于保持旧入口和旧调用方行为不变。
     *
     * @return 不会选择 Q 动作、不写文件的会话
     */
    public static QLearningSession disabled() {
        try {
            return open(new QLearningConfig(
                    QMode.OFF, 0L, 0.20, 0.85, 1.0, 0.05, 0.995,
                    0, 8, 12, 24, false, 2, 0.15, false), null);
        } catch (IOException exception) {
            throw new IllegalStateException("创建关闭状态的 Q-learning 会话失败", exception);
        }
    }

    /**
     * 返回指定阶段的 Q 控制器。
     *
     * @param phase 当前 NFP 拼接或优先级排样阶段
     * @return 与阶段一一对应的独立 Q 表控制器
     */
    public TabularQController controller(SearchPhase phase) {
        return controllers.get(phase);
    }

    /** @return 当前会话配置。 */
    public QLearningConfig config() {
        return config;
    }

    /** @return 当前会话是否实际启用了 Q-learning。 */
    public boolean isEnabled() {
        return config.mode() != QMode.OFF;
    }

    /**
     * 开始记录一个案例的 NFP 与排样 Q 决策，以便在取得最终排样结果后施加终局奖励。
     *
     * @param episodeId 当前案例的稳定标识；通常使用不含扩展名的案例文件名。
     */
    public void beginEpisode(String episodeId) {
        if (!isEnabled()) {
            return;
        }
        activeEpisodeId = normalizeEpisodeId(episodeId);
        episodeDecisions.put(activeEpisodeId, new ArrayList<>());
    }

    /**
     * 激活已经由 NFP 阶段创建的案例决策记录，供后续矩形排样继续追加决策。
     *
     * @param episodeId 当前案例的稳定标识；通常使用不含扩展名的案例文件名。
     */
    public void activateEpisode(String episodeId) {
        if (!isEnabled()) {
            return;
        }
        activeEpisodeId = normalizeEpisodeId(episodeId);
        episodeDecisions.computeIfAbsent(activeEpisodeId, ignored -> new ArrayList<>());
    }

    /**
     * 记录一项已执行的局部 Q 决策，等待案例结束时接受终局奖励强化。
     *
     * @param phase 执行动作所属的 NFP 拼接或排样阶段。
     * @param stateKey 执行动作时的离散状态编号。
     * @param actionIndex 已执行动作在对应阶段 Q 表中的索引。
     */
    public void recordDecision(SearchPhase phase, int stateKey, int actionIndex) {
        if (config.mode() != QMode.TRAIN || activeEpisodeId == null) {
            return;
        }
        List<DecisionReference> decisions = episodeDecisions.get(activeEpisodeId);
        if (decisions != null && decisions.size() < MAX_TERMINAL_DECISIONS_PER_EPISODE) {
            decisions.add(new DecisionReference(phase, stateKey, actionIndex));
        }
    }

    /**
     * 使用当前活动案例的最终排样结果，对其 NFP 和排样 Q 决策追加一次终局奖励强化。
     *
     * @param result 当前案例完整的优先级排样结果，包含 Sp、So、利用率和实际求解时间。
     */
    public void completeActiveEpisode(ExecutionResult result) {
        if (config.mode() != QMode.TRAIN || activeEpisodeId == null) {
            return;
        }
        List<DecisionReference> decisions = episodeDecisions.remove(activeEpisodeId);
        activeEpisodeId = null;
        if (decisions == null || decisions.isEmpty() || result == null) {
            return;
        }

        double terminalReward = calculateTerminalReward(result);
        for (int index = 0; index < decisions.size(); index++) {
            DecisionReference decision = decisions.get(index);
            // 早期决策仍能获得终局反馈，但更接近最终解的动作权重更高。
            double eligibility = 0.25 + 0.75 * (index + 1.0) / decisions.size();
            controller(decision.phase()).reinforceTerminal(
                    decision.stateKey(), decision.actionIndex(), terminalReward, eligibility);
        }
    }

    /**
     * 记录一条 Q 决策；未开启轨迹时本方法不写文件。
     *
     * @param phase 当前搜索阶段
     * @param stateKey 当前状态编号
     * @param actionName 已执行动作名称
     * @param epsilon 动作选择时探索率
     * @param reward 本次奖励
     * @param nextStateKey 下一状态编号
     * @param terminal 是否终止状态
     * @param candidateCount 当前候选数量
     */
    public void trace(SearchPhase phase,
                      int stateKey,
                      String actionName,
                      double epsilon,
                      double reward,
                      int nextStateKey,
                      boolean terminal,
                      int candidateCount) {
        if (traceWriter != null) {
            traceWriter.write(phase, stateKey, actionName, epsilon, reward,
                    nextStateKey, terminal, candidateCount);
        }
    }

    /**
     * 关闭会话；TRAIN 模式保存 Q 表，所有模式都关闭已打开的轨迹文件。
     *
     * @throws IOException 当 Q 表或轨迹文件无法关闭时抛出
     */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        if (config.mode() == QMode.TRAIN) {
            try {
                saveControllers(storageDirectory.resolve(TABLE_FILE_NAME), controllers);
            } catch (IOException exception) {
                failure = exception;
            }
        }
        if (traceWriter != null) {
            try {
                traceWriter.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void loadControllers(Path tableFile,
                                        Map<SearchPhase, TabularQController> controllers) throws IOException {
        if (!Files.isRegularFile(tableFile)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(tableFile)) {
            properties.load(inputStream);
        }
        for (Map.Entry<SearchPhase, TabularQController> entry : controllers.entrySet()) {
            entry.getValue().loadFrom(properties, entry.getKey().name().toLowerCase());
        }
    }

    private static void saveControllers(Path tableFile,
                                        Map<SearchPhase, TabularQController> controllers) throws IOException {
        Files.createDirectories(tableFile.getParent());
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", "2");
        for (Map.Entry<SearchPhase, TabularQController> entry : controllers.entrySet()) {
            entry.getValue().saveTo(properties, entry.getKey().name().toLowerCase());
        }
        try (OutputStream outputStream = Files.newOutputStream(tableFile)) {
            properties.store(outputStream, "Q-learning packing tables");
        }
    }

    /**
     * 把最终排样目标转换为有界终局奖励。
     *
     * <p>奖励按目标优先级分层：先评价优先容器数 Sp，再评价实际使用容器数 Nb；
     * 只有在这两个主目标之后，才以等效容器数 N、真实平均利用率 Uagv 和时间作为
     * 次级指标。这样 N 因最低利用率容器波动而略有下降时，不会掩盖 Sp 或 Nb 增加
     * 所代表的实际退化。所有效率均经归一化，因而不同规模案例可以共享 Q 表。</p>
     *
     * @param result 当前案例完整排样结果。
     * @return 位于 {@code [-1, 1]} 的终局奖励；越大代表最终目标质量越好。
     */
    private double calculateTerminalReward(ExecutionResult result) {
        // 确保从任意排样入口结束时，Q-learning 都使用最终布局的真实面积统计。
        result.setActualUtilizationMetrics();
        int priorityBoards = Math.max(0, result.priorityBoardCount);
        int actualBoardCount = Math.max(0, result.solutions.size());
        double totalMaterialArea = 0.0;
        double priorityMaterialArea = 0.0;
        double largestBoardArea = 0.0;

        for (Solution solution : result.solutions) {
            // BeamSearch 容器面积为内部的 10 倍坐标面积，转换到平方毫米后
            // 才能与 NFP 多边形真实面积相除。
            largestBoardArea = Math.max(largestBoardArea, solution.getContainerArea() / 100.0);
            for (PlacedCuboid placedCuboid : solution.getPlacedCuboid()) {
                double area = Math.max(0.0, placedCuboid.getActualWorkpieceArea());
                totalMaterialArea += area;
                if (isPriorityColor(placedCuboid.box.color)) {
                    priorityMaterialArea += area;
                }
            }
        }

        double safeBoardArea = Math.max(1.0, largestBoardArea);
        int totalLowerBound = totalMaterialArea <= 0.0
                ? 0
                : Math.max(1, (int) Math.ceil(totalMaterialArea / safeBoardArea));
        int priorityLowerBound = priorityMaterialArea <= 0.0
                ? 0
                : Math.max(1, (int) Math.ceil(priorityMaterialArea / safeBoardArea));
        double priorityEfficiency = priorityLowerBound == 0
                ? 1.0
                : clamp((double) priorityLowerBound / Math.max(1, priorityBoards), 0.0, 1.0);
        double actualBoardEfficiency = totalLowerBound == 0
                ? 1.0
                : clamp((double) totalLowerBound / Math.max(1, actualBoardCount), 0.0, 1.0);
        double equivalentContainerEfficiency = totalLowerBound == 0
                ? 1.0
                : clamp((double) totalLowerBound
                / Math.max(1.0, result.equivalentContainerCount), 0.0, 1.0);
        double actualUtilization = clamp(result.actualAverageUtilization, 0.0, 1.0);
        double timePenalty = clamp(result.totalSolveTimeMs
                / Math.max(1.0, PackingRuntimeConfig.totalSolveTimeMs()), 0.0, 1.0);

        // 权重遵循 Sp -> Nb -> N -> Uagv 的主次顺序。N 与 Uagv 仅在板材数
        // 相近时用于区分布局质量，不能再单独主导终局奖励。
        double quality = 0.58 * priorityEfficiency
                + 0.30 * actualBoardEfficiency
                + 0.07 * equivalentContainerEfficiency
                + 0.04 * actualUtilization
                - 0.01 * timePenalty;
        return clamp(2.0 * quality - 1.0, -1.0, 1.0);
    }

    /**
     * 判断结果工件颜色是否表示优先件。
     *
     * @param color 排样输入中保留的工件颜色字段。
     * @return 颜色为 {@code "1"} 或布尔文本 {@code "true"} 时返回 {@code true}。
     */
    private boolean isPriorityColor(String color) {
        return "1".equals(color) || "true".equalsIgnoreCase(color);
    }

    /**
     * 将案例标识规范化为空值安全的内部键。
     *
     * @param episodeId 调用方提供的案例标识。
     * @return 可作为会话 Map 键的非空案例标识。
     */
    private String normalizeEpisodeId(String episodeId) {
        return episodeId == null || episodeId.isBlank() ? "unnamed-episode" : episodeId.trim();
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
     * 记录一次已发生、等待终局强化的 Q 动作。
     *
     * @param phase 动作所属阶段。
     * @param stateKey 动作发生时的离散状态编号。
     * @param actionIndex 动作在所属阶段 Q 表中的索引。
     */
    private record DecisionReference(SearchPhase phase, int stateKey, int actionIndex) {
    }

    /**
     * 返回指定搜索阶段可用的 Q-learning 动作数量。
     *
     * @param phase 当前 NFP 拼接或矩形排样阶段。
     * @return 与该阶段动作枚举数量一致的正整数。
     */
    private static int actionCountFor(SearchPhase phase) {
        return switch (phase) {
            case NFP_STITCH -> NFP_ACTION_COUNT;
            case FILL_MODE -> FILL_MODE_ACTION_COUNT;
            case FILL_SPACE -> FILL_SPACE_ACTION_COUNT;
            case FILL_ITEM -> FILL_ITEM_ACTION_COUNT;
            default -> PACKING_ACTION_COUNT;
        };
    }
}
