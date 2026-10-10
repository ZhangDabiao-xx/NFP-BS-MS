package org.example.nfp;

/** NFP-only runtime switch; Packing Agent remains separately disabled. */
public final class NfpAgentConfig {
    private static final boolean ENABLE_NFP_STITCHING_AGENT = true;
    /** Agent trials keep three independent profile branches, so each profile uses width three. */
    public static final int AGENT_ROOT_BEAM_WIDTH = 3;
    /** Geometric generation remains wider than the selected root Beam to retain legal placements. */
    public static final int AGENT_GENERATION_CAP = 20;
    /** Bounded global-plan Beam used by each Agent profile branch. */
    public static final int AGENT_GLOBAL_PLAN_WIDTH = 20;
    /** Experiment runner uses this Agent-only property to force a portfolio of distinct strategy families. */
    public static final String REQUIRED_STRATEGY_FAMILY_PROPERTY = "nfp.agent.requiredStrategyFamily";
    private NfpAgentConfig() { }
    /** System property is used only by the explicit experiment application. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("nfp.agent.enabled",
                Boolean.toString(ENABLE_NFP_STITCHING_AGENT)));
    }

    /** Empty means an ordinary Agent invocation may use the family returned by the validated profile. */
    public static String requiredStrategyFamily() {
        return System.getProperty(REQUIRED_STRATEGY_FAMILY_PROPERTY, "").strip();
    }
}
