package org.example.nfp;

/** NFP-only runtime switch; Packing Agent remains separately disabled. */
public final class NfpAgentConfig {
    private static final boolean ENABLE_NFP_STITCHING_AGENT = true;
    private NfpAgentConfig() { }
    /** System property is used only by the explicit experiment application. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("nfp.agent.enabled",
                Boolean.toString(ENABLE_NFP_STITCHING_AGENT)));
    }
}
