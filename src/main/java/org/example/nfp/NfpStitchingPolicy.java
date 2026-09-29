package org.example.nfp;

/**
 * Compatibility marker for the frozen pre-profile baseline only.
 *
 * <p>The former multi-policy Agent selection has been removed. New code must use
 * {@code NfpSelectionModel}; this type remains solely so older stitch overloads retain their
 * public shape while reproducing the historical balanced baseline.</p>
 */
@Deprecated(forRemoval = true)
public enum NfpStitchingPolicy {
    BALANCED;

    public String id() { return "legacy-baseline"; }
    public boolean reserveCriticalSmallItems() { return true; }
    public boolean preserveSmallRectanglesForPacking() { return false; }
    public boolean cavityFirst() { return true; }
    public boolean preserveDiverseRootCandidates() { return false; }
    public double opportunityCostMultiplier() { return 1.0; }
    public String description() { return "冻结的历史基准；不能由 Agent 选择。"; }
    public static NfpStitchingPolicy fromId(String id) {
        if ("legacy-baseline".equals(id) || "balanced".equals(id)) return BALANCED;
        throw new IllegalArgumentException("历史基准不支持策略: " + id);
    }
}
