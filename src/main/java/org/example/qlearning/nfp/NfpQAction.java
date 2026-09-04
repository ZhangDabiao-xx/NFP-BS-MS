package org.example.qlearning.nfp;

/** NFP 根 Beam 中可由 Q-learning 选择的已验证拼接候选排序动作。 */
public enum NfpQAction {
    /** 延续原有规则：优先最近一次 NFP 综合评分。 */
    COMBINED_SCORE,
    /** 优先保留真实填入已有外接框凹腔的候选。 */
    CAVITY_INSERTION,
    /** 优先保留组合块填充率较高的候选。 */
    FILL_RATE,
    /** 优先保留外接矩形面积较小、更加紧凑的候选。 */
    COMPACT_BOUNDING_BOX,
    /** 优先保留已组合成员较多、可减少后续单块数量的候选。 */
    MEMBER_EXPANSION,
    /** 综合凹腔利用、填充率、成员数和最近一次 NFP 评分。 */
    BALANCED
}
