package org.example.qlearning;

/**
 * 使用独立 Q 表的排样阶段。
 */
public enum SearchPhase {
    /** 优先件新板排样阶段。 */
    PRIORITY,
    /** 普通件填入既有优先件板材阶段。 */
    FILL,
    /** 剩余普通件新板排样阶段。 */
    ORDINARY
}
