package org.example.nfp;

/** NFP-only runtime switch; Packing Agent remains separately disabled. */
public final class NfpAgentConfig {
    private static final boolean ENABLE_NFP_STITCHING_AGENT = true;
    private NfpAgentConfig() { }
    public static boolean isEnabled() { return ENABLE_NFP_STITCHING_AGENT; }
}
