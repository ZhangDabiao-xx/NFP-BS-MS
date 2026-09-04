package org.example.qlearning;

/**
 * Q-learning 控制器的运行模式。
 */
public enum QMode {
    /** 完全使用现有固定启发式，不创建 Q 决策或更新 Q 表。 */
    OFF,
    /** 使用 ε-greedy 选择动作、更新 Q 表并在运行结束后保存。 */
    TRAIN,
    /** 读取既有 Q 表并始终选择贪婪动作，不修改 Q 表。 */
    EVALUATE
}
