package org.example.qlearning.packing;

/** 普通件填入既有优先件板材（Sp）时可学习选择的阶段级策略。 */
public enum FillInsertionMode {
    /**
     * 逐件尝试普通件：先以矩形面积作必要条件筛选，再将当前 Sp 全部工件与候选件整体重排。
     * 只有全部工件能装入同一张板材时才接受该普通件；这是 Q-learning 关闭时的默认基线策略。
     */
    CANDIDATE_ITEM_REPACK,
    /** 不重排优先件，普通件沿用原有靠近板材外角的放置方式。 */
    LEGACY_CORNER_INSERTION,
    /** 重排最低利用率 Sp 中的优先件，并在重排时固定使用空闲空间左下角。 */
    REPACK_LOWEST_UTILIZATION_BOARD,
    /** 不重排优先件，普通件统一使用各自空闲空间的左下角放置。 */
    LOWER_LEFT_INSERTION
}
