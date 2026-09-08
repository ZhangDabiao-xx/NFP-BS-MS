package org.example.qlearning.packing;

/** 普通件插入 Sp 时用于排序极大空闲矩形的动作。 */
public enum FillSpaceAction {
    /** 优先尝试面积较大的极大空闲矩形。 */
    LARGEST_AREA_SPACE,
    /** 优先尝试最长边较长的极大空闲矩形。 */
    LONGEST_EDGE_SPACE
}
