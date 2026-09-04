package org.example.qlearning;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 以 CSV 记录 Q-learning 决策轨迹，供训练结果复现和奖励诊断使用。
 */
public final class QTraceWriter implements AutoCloseable {

    private final BufferedWriter writer;

    /**
     * 创建轨迹文件并写入表头。
     *
     * @param traceFile 要写入的 CSV 文件路径；父目录不存在时会自动创建
     * @throws IOException 当轨迹文件无法创建时抛出
     */
    public QTraceWriter(Path traceFile) throws IOException {
        Files.createDirectories(traceFile.getParent());
        writer = Files.newBufferedWriter(traceFile, StandardCharsets.UTF_8);
        writer.write("phase,state,action,epsilon,reward,nextState,terminal,candidateCount\n");
    }

    /**
     * 追加一条状态转移记录。
     *
     * @param phase 当前搜索阶段
     * @param stateKey 执行动作前的离散状态
     * @param actionName 动作枚举名称
     * @param epsilon 动作选择时的探索率
     * @param reward 本次状态转移得到的奖励
     * @param nextStateKey 动作执行后的离散状态
     * @param terminal 当前状态转移是否终止
     * @param candidateCount 当前动作参与排序的候选数量
     */
    public synchronized void write(SearchPhase phase,
                                   int stateKey,
                                   String actionName,
                                   double epsilon,
                                   double reward,
                                   int nextStateKey,
                                   boolean terminal,
                                   int candidateCount) {
        try {
            writer.write(phase + "," + stateKey + "," + actionName + ","
                    + epsilon + "," + reward + "," + nextStateKey + ","
                    + terminal + "," + candidateCount + "\n");
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入 Q-learning 轨迹", exception);
        }
    }

    /**
     * 关闭轨迹文件并刷新缓冲内容。
     *
     * @throws IOException 当关闭文件失败时抛出
     */
    @Override
    public void close() throws IOException {
        writer.close();
    }
}
