package org.example.beamsearch.application;

/**
 * 排样阶段使用的固定运行时预算。
 *
 * <p>基准程序不读取 JVM 参数。需要调整预算时，统一修改本类的常量，保证所有
 * 案例都在相同条件下求解。</p>
 */
public final class PackingRuntimeConfig {

    /** Optional per-process budget used only by the bounded NFP Agent experiment runner. */
    public static final String AGENT_EXPERIMENT_BUDGET_PROPERTY = "nfp.agent.packingBudgetMs";

    /** 单案例全局重排优化预算：十分钟。 */
    public static final long DEFAULT_TOTAL_SOLVE_TIME_MS = 600_000L;
    /** 动态 Beam Search 不限制最大宽度。 */
    public static final int DEFAULT_MAX_BEAM_WIDTH = Integer.MAX_VALUE;
    /** 全局重排除总预算外不再额外截断。 */
    public static final long DEFAULT_REPACK_TIME_LIMIT_MS = Long.MAX_VALUE;
    /** 单次普通件候选整体重排验证的最长时间。 */
    public static final long DEFAULT_CANDIDATE_REPACK_TIME_LIMIT_MS = 5_000L;

    private PackingRuntimeConfig() {
    }

    /**
     * Returns the normal baseline budget unless the experiment runner explicitly sets its
     * Agent-only override.  Baseline executions never set this property, so their 600-second
     * budget remains unchanged.
     */
    public static long totalSolveTimeMs() {
        String value = System.getProperty(AGENT_EXPERIMENT_BUDGET_PROPERTY);
        if (value != null) {
            try {
                long configured = Long.parseLong(value);
                if (configured > 0) return configured;
            } catch (NumberFormatException ignored) {
                // Invalid external configuration must not make a packing run fail.
            }
        }
        return DEFAULT_TOTAL_SOLVE_TIME_MS;
    }

    /** @return 动态 Beam Search 可达到的最大宽度。 */
    public static int maxBeamWidth() {
        return DEFAULT_MAX_BEAM_WIDTH;
    }

    /** @return 一次全局重排可使用的最大时间，单位为毫秒。 */
    public static long repackTimeLimitMs() {
        return DEFAULT_REPACK_TIME_LIMIT_MS;
    }

    /** @return 单次普通件候选整体重排验证的最大时间，单位为毫秒。 */
    public static long candidateRepackTimeLimitMs() {
        return DEFAULT_CANDIDATE_REPACK_TIME_LIMIT_MS;
    }

    /**
     * 将调度器分配给某个重排阶段的时间受固定上限约束。
     *
     * @param scheduledTimeMs 当前总预算调度器分配的重排时间，单位为毫秒。
     * @return 不超过固定上限的非负时间预算。
     */
    public static long capRepackTimeMs(long scheduledTimeMs) {
        return Math.max(0L, Math.min(Math.max(0L, scheduledTimeMs), repackTimeLimitMs()));
    }
}
