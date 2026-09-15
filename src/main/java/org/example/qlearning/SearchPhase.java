package org.example.qlearning;

/** 使用独立 Q 表的矩形排样阶段。NFP 拼接始终采用固定的确定性规则。 */
public enum SearchPhase {
    /** 优先件新板排样阶段。 */
    PRIORITY,
    /** 普通件填入既有优先件板材阶段。 */
    FILL,
    /** 普通件填入 Sp 前选择整体插入模式的阶段。 */
    FILL_MODE,
    /** 普通件填入 Sp 时选择极大空闲空间排序策略的阶段。 */
    FILL_SPACE,
    /** 普通件填入 Sp 时选择候选物品排序策略的阶段。 */
    FILL_ITEM,
    /** 剩余普通件新板排样阶段。 */
    ORDINARY
}
