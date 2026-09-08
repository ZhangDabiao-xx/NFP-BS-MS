package org.example.qlearning.packing;

/** 普通件填入既有优先件板材（Sp）时可学习选择的阶段级策略。 */
public enum FillInsertionMode {
    /** 不重排优先件，普通件沿用原有靠近板材外角的放置方式。 */
    LEGACY_CORNER_INSERTION,
    /** 重排最低利用率 Sp 中的优先件，并在重排时固定使用空闲空间左下角。 */
    REPACK_LOWEST_UTILIZATION_BOARD,
    /** 不重排优先件，普通件统一使用各自空闲空间的左下角放置。 */
    LOWER_LEFT_INSERTION
}
