package org.example.beamsearch.application;

/**
 * 排样阶段的运行时预算配置。
 *
 * <p>所有参数都通过 JVM {@code -D} 属性读取，因此训练与评估可使用不同预算，
 * 无需修改源码或改变统一入口的案例路径参数。</p>
 */
public final class PackingRuntimeConfig {

    /** 默认单案例全局重排优化预算：十分钟。 */
    public static final long DEFAULT_TOTAL_SOLVE_TIME_MS = 600_000L;
    /** 默认不限制动态 Beam 宽度，以兼容原有求解质量。 */
    public static final int DEFAULT_MAX_BEAM_WIDTH = Integer.MAX_VALUE;
    /** 默认不额外截断全局重排时间，仅受总预算限制。 */
    public static final long DEFAULT_REPACK_TIME_LIMIT_MS = Long.MAX_VALUE;
    /** 单次“Sp 全部工件 + 一个普通件”验证性重排的默认最长时间。 */
    public static final long DEFAULT_CANDIDATE_REPACK_TIME_LIMIT_MS = 5_000L;

    private PackingRuntimeConfig() {
    }

    /**
     * 返回一个案例全局重排优化共享的总时间预算。
     *
     * <p>优先件和普通件的初始 Beam 排样、普通件插入 Sp 均不扣减此预算；
     * 这些阶段仍会单独统计耗时。每次
     * “Sp 全部工件 + 一个普通件”的验证重排仍由
     * {@link #candidateRepackTimeLimitMs()} 保护。</p>
     *
     * @return JVM 参数 {@code packing.totalSolveTimeMs} 的正整数毫秒值；未设置时为十分钟。
     */
    public static long totalSolveTimeMs() {
        return parsePositiveLong("packing.totalSolveTimeMs", DEFAULT_TOTAL_SOLVE_TIME_MS);
    }

    /**
     * 返回动态 Beam Search 可达到的最大宽度。
     *
     * @return JVM 参数 {@code packing.maxBeamWidth} 的值；小于 4 时自动提升为 4。
     */
    public static int maxBeamWidth() {
        long parsed = parsePositiveLong("packing.maxBeamWidth", DEFAULT_MAX_BEAM_WIDTH);
        return (int) Math.max(4L, Math.min(Integer.MAX_VALUE, parsed));
    }

    /**
     * 返回一次全局重排允许消耗的最大时间。
     *
     * @return JVM 参数 {@code packing.repackTimeLimitMs} 的正整数毫秒值；未设置时不额外截断。
     */
    public static long repackTimeLimitMs() {
        return parsePositiveLong("packing.repackTimeLimitMs", DEFAULT_REPACK_TIME_LIMIT_MS);
    }

    /**
     * 返回普通件候选整体重排验证允许使用的单次时间上限。
     *
     * <p>插入阶段不占用 {@code packing.totalSolveTimeMs} 的全局优化预算；
     * 此参数只限制一次候选验证，避免一个难放普通件无限占用插入阶段。</p>
     *
     * @return JVM 参数 {@code packing.candidateRepackTimeLimitMs} 的正整数毫秒值；未设置时为 5000 毫秒。
     */
    public static long candidateRepackTimeLimitMs() {
        return parsePositiveLong("packing.candidateRepackTimeLimitMs",
                DEFAULT_CANDIDATE_REPACK_TIME_LIMIT_MS);
    }

    /**
     * 将调度器分配给某个重排阶段的时间再受运行时上限约束。
     *
     * @param scheduledTimeMs 当前总预算调度器分配的重排时间。
     * @return 不超过 {@link #repackTimeLimitMs()} 的非负时间预算。
     */
    public static long capRepackTimeMs(long scheduledTimeMs) {
        return Math.max(0L, Math.min(Math.max(0L, scheduledTimeMs), repackTimeLimitMs()));
    }

    private static long parsePositiveLong(String propertyName, long fallback) {
        try {
            long value = Long.parseLong(System.getProperty(propertyName, String.valueOf(fallback)));
            return value > 0L ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
