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
    public static final long DEFAULT_TOTAL_SOLVE_TIME_MS = 800_000L;
    /** 动态 Beam Search 不限制最大宽度。 */
    public static final int DEFAULT_MAX_BEAM_WIDTH = Integer.MAX_VALUE;
    /** 全局重排除总预算外不再额外截断。 */
    public static final long DEFAULT_REPACK_TIME_LIMIT_MS = Long.MAX_VALUE;
    /** 单次普通件候选整体重排验证的最长时间。 */
    public static final long DEFAULT_CANDIDATE_REPACK_TIME_LIMIT_MS = 5_000L;
    /** 每张新板在初始 Beam Search 中的常规搜索预算。 */
    public static final long DEFAULT_INITIAL_BOARD_TIME_MS = 1_000L;
    /** 初始 Beam Search 的最大宽度。 */
    public static final int DEFAULT_INITIAL_MAX_BEAM_WIDTH = 128;
    /** 普通件尝试插入优先板阶段的总时间上限。 */
    public static final long DEFAULT_ORDINARY_INSERTION_TIME_MS = 100_000L;

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
     * 返回初始排样的单板搜索预算。
     *
     * <p>该策略恢复旧程序的可伸缩预算：通常每张板搜索一秒；当面积下界
     * 已超过总优化秒数的一半时，再按 {@code 总预算 / (2 * 下界)} 缩短。这样
     * 即使有数百张板，初始排样也不会无限放大。优先件、普通件和 NFP 组块
     * 均使用这一策略；单板搜索到时后由调用方的确定性兜底继续排完工件。</p>
     *
     * @param lowerBoundByArea 由矩形总面积得到的板材数下界
     * @param totalOptimizationTimeMs 当前案例的全局重排预算
     * @return 一张新板允许使用的搜索时间，至少为一毫秒
     */
    public static long initialBoardTimeMs(int lowerBoundByArea,
                                          long totalOptimizationTimeMs) {
        long totalBudgetMs = Math.max(1L, totalOptimizationTimeMs);
        long threshold = totalBudgetMs / 2_000L;
        if (lowerBoundByArea <= threshold) {
            return DEFAULT_INITIAL_BOARD_TIME_MS;
        }

        long scaledBudget = totalBudgetMs / (2L * Math.max(1, lowerBoundByArea));
        return Math.max(1L, Math.min(DEFAULT_INITIAL_BOARD_TIME_MS, scaledBudget));
    }

    /** @return 初始新板排样可使用的最大 Beam 宽度。 */
    public static int initialMaxBeamWidth() {
        return DEFAULT_INITIAL_MAX_BEAM_WIDTH;
    }

    /** @return 普通件插入优先板阶段的总时间上限，单位为毫秒。 */
    public static long ordinaryInsertionTimeMs() {
        return DEFAULT_ORDINARY_INSERTION_TIME_MS;
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
