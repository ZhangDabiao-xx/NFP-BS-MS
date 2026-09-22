package org.example.beamsearch.common;

/**
 * 一次全局重排优化的轻量监控数据。
 *
 * <p>该对象只记录计数、时间和停止原因，不参与任何搜索决策，因此不会改变
 * BeamSearch 的排样结果或时间预算。</p>
 */
public final class RepackStatistics {

    /** 是否真正进入了 ImproveByRepack。 */
    public boolean executed;
    /** 未执行时的明确原因；执行完成时为实际停止原因。 */
    public String stopReason = "not_started";
    /** 调度器传入的本阶段时间上限，单位为毫秒。 */
    public long requestedTimeLimitMs;
    /** 本阶段实际墙钟耗时，单位为毫秒。 */
    public long elapsedTimeMs;
    /** 重排前后的板材数量。 */
    public int boardCountBefore;
    public int boardCountAfter;
    /** 外层重排循环次数及其中完成的无减板扫描轮数。 */
    public long outerLoopIterations;
    public int noImprovementSweeps;
    /** 被选为待移出板材的次数，以及生成的板材组合候选数量。 */
    public long candidateBoardAttempts;
    public long pairCandidatesGenerated;
    /** 实际执行的组合重排次数及其中产生严格改进的次数。 */
    public long pairRepackAttempts;
    public long successfulPairRepackAttempts;
    /** 成功减少板材数量的次数。 */
    public long boardReductions;
    /** 是否因达到本阶段时间上限退出。 */
    public boolean timeLimitReached;

    /** 创建一个明确标识为未执行的统计对象。 */
    public static RepackStatistics notRun(String reason) {
        RepackStatistics statistics = new RepackStatistics();
        statistics.stopReason = reason == null || reason.isBlank() ? "not_run" : reason;
        return statistics;
    }
}
