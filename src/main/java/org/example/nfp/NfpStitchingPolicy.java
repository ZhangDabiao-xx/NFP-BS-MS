package org.example.nfp;

/** 固定的基准 NFP 搜索保护规则；不作为外部策略选择接口。 */
enum NfpStitchingPolicy {
    BALANCED;

    public String id() { return "legacy-baseline"; }
    public boolean reserveCriticalSmallItems() { return true; }
    public boolean preserveSmallRectanglesForPacking() { return false; }
    public boolean cavityFirst() { return true; }
    public boolean preserveDiverseRootCandidates() { return false; }
    public double opportunityCostMultiplier() { return 1.0; }
}
