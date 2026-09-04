package org.example.qlearning.packing;

/**
 * 矩形 Beam Search 可由 Q-learning 选择的候选排序动作。
 */
public enum PackingQAction {
    /** 最小化候选所在空间的面积浪费。 */
    BEST_AREA_FIT,
    /** 最小化候选放置后的短边剩余量。 */
    BEST_SHORT_SIDE_FIT,
    /** 最大化与板边及已放置块的接触长度。 */
    MAX_CONTACT,
    /** 最小化局部空间切分产生的碎片数量。 */
    MIN_FRAGMENTATION,
    /** 优先用候选填补较小的可用空间。 */
    SMALL_GAP_MATCH,
    /** 优先放置最佳位置与次优位置差异较大的难决策块。 */
    REGRET_2,
    /** 在面积适配较好的 Top 候选中按固定随机种子抽样。 */
    DIVERSIFY
}
