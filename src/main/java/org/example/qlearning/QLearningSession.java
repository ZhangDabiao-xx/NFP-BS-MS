package org.example.qlearning;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * 一次端到端求解期间共享的 Q-learning 会话。
 *
 * <p>会话为优先件、填充和普通件阶段维护独立控制器，并在 TRAIN 模式结束时
 * 保存 Q 表。OFF 模式不会创建文件，也不会改变原启发式流程。</p>
 */
public final class QLearningSession implements AutoCloseable {

    private static final String TABLE_FILE_NAME = "packing-q-tables.properties";
    private static final String TRACE_FILE_NAME = "packing-q-trace.csv";
    private static final int PACKING_ACTION_COUNT = 7;

    private final QLearningConfig config;
    private final Path storageDirectory;
    private final Map<SearchPhase, TabularQController> controllers;
    private final QTraceWriter traceWriter;

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
                    PACKING_ACTION_COUNT,
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
                    0, 8, 12, 2, 0.15, false), null);
        } catch (IOException exception) {
            throw new IllegalStateException("创建关闭状态的 Q-learning 会话失败", exception);
        }
    }

    /**
     * 返回指定阶段的 Q 控制器。
     *
     * @param phase 当前优先级排样阶段
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
        properties.setProperty("schemaVersion", "1");
        for (Map.Entry<SearchPhase, TabularQController> entry : controllers.entrySet()) {
            entry.getValue().saveTo(properties, entry.getKey().name().toLowerCase());
        }
        try (OutputStream outputStream = Files.newOutputStream(tableFile)) {
            properties.store(outputStream, "Q-learning packing tables");
        }
    }
}
