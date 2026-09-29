package org.example.nfp;

/**
 * NFP 拼接阶段允许 Agent 选择的受限策略集合。
 *
 * <p>策略只调节已经通过 NFP 几何校验的候选块如何竞争；不会放宽重叠、连通性、
 * 旋转或板材尺寸约束。因此模型不能通过策略绕过现有的确定性安全检查。</p>
 */
public enum NfpStitchingPolicy {

    /** 使用原有的关键凹腔保护与机会成本平衡规则。 */
    BALANCED("balanced", true, false, true, false, 1.0,
            "平衡凹腔填充与小件机会成本，适用于没有明显资源稀缺特征的案例。"),

    /** 让真实凹腔填充优先于普通高填充组合。 */
    CAVITY_FIRST("cavity_first", true, false, true, false, 1.5,
            "优先保留关键凹腔的候选与小件资源，适用于低填充率异形件较多的案例。"),

    /**
     * 限制小矩形被第一阶段拼接消耗，给第二阶段排样保留填缝资源。
     * 这对应“小件很少时，不应仅为局部凹腔填充率而耗尽小件”的情形。
     */
    PRESERVE_SCARCE_SMALL_RECTANGLES("preserve_scarce_small_rectangles", false, true, false, false, 2.0,
            "保留稀缺小矩形用于后续排样缝隙，优先尝试不消耗小件的较大件拼接。"),

    /** 优先选择外接框紧凑、局部填充率高的候选，不额外偏向凹腔资源。 */
    COMPACT_BLOCKS_FIRST("compact_blocks_first", false, false, false, false, 0.2,
            "优先形成紧凑组合块，适用于小件供给充足且异形凹腔不明显的案例。"),

    /**
     * 为同一根凹腔保留局部高分与少消耗小矩形的替代分支。
     *
     * <p>该策略适合“两个长条小矩形局部填充率更高，但一个较宽矩形能保留填缝资源”
     * 的场景。最多保留三个不同资源结构的根候选，交由后续全局 Beam 和最终排样结果检验。</p>
     */
    PARETO_CAVITY_ALTERNATIVES("pareto_cavity_alternatives", true, false, true, true, 1.0,
            "同时保留高局部评分和少消耗小矩形的凹腔方案，避免有效终止分支被过早淘汰。");

    private final String id;
    private final boolean reserveCriticalSmallItems;
    private final boolean preserveSmallRectanglesForPacking;
    private final boolean cavityFirst;
    private final boolean preserveDiverseRootCandidates;
    private final double opportunityCostMultiplier;
    private final String description;

    NfpStitchingPolicy(String id,
                        boolean reserveCriticalSmallItems,
                        boolean preserveSmallRectanglesForPacking,
                        boolean cavityFirst,
                        boolean preserveDiverseRootCandidates,
                        double opportunityCostMultiplier,
                        String description) {
        this.id = id;
        this.reserveCriticalSmallItems = reserveCriticalSmallItems;
        this.preserveSmallRectanglesForPacking = preserveSmallRectanglesForPacking;
        this.cavityFirst = cavityFirst;
        this.preserveDiverseRootCandidates = preserveDiverseRootCandidates;
        this.opportunityCostMultiplier = opportunityCostMultiplier;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public boolean reserveCriticalSmallItems() {
        return reserveCriticalSmallItems;
    }

    public boolean preserveSmallRectanglesForPacking() {
        return preserveSmallRectanglesForPacking;
    }

    public boolean cavityFirst() {
        return cavityFirst;
    }

    /** 是否在根 Beam 中保留资源结构不同的候选分支。 */
    public boolean preserveDiverseRootCandidates() {
        return preserveDiverseRootCandidates;
    }

    public double opportunityCostMultiplier() {
        return opportunityCostMultiplier;
    }

    public String description() {
        return description;
    }

    /** 根据 Agent 输出的稳定策略 ID 解析策略；未知值直接拒绝。 */
    public static NfpStitchingPolicy fromId(String id) {
        for (NfpStitchingPolicy policy : values()) {
            if (policy.id.equals(id)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("未知 NFP 拼接策略: " + id);
    }
}
