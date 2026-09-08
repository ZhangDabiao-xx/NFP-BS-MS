package org.example.qlearning.packing;

/** 普通件插入 Sp 时用于排序可放置候选物品的动作。 */
public enum FillItemAction {
    /** 优先尝试外接矩形面积较大的候选物品。 */
    LARGEST_AREA_ITEM,
    /** 优先尝试最长边较长的候选物品。 */
    LONGEST_EDGE_ITEM,
    /** 优先尝试论文评分 {@code wh(1+w^2/W^2+h^2/H^2)} 较大的候选物品。 */
    MAX_PRIORITY_SCORE_ITEM
}
