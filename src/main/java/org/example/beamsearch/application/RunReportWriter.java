package org.example.beamsearch.application;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.example.beamsearch.common.ExecutionResult;
import org.example.beamsearch.common.PlacedCuboid;
import org.example.beamsearch.common.RepackStatistics;
import org.example.beamsearch.common.Solution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 将一次排样的关键结果写成供 Agent 读取的 JSON 报告。
 *
 * <p>本类只读取最终 {@link ExecutionResult}，不会改变任何搜索或排样决策。
 * 只写入当前程序已经计算出来的指标，避免报告中出现未经统计的推测数据。</p>
 */
public final class RunReportWriter {

    /** 每个案例结果目录下保存 LLM 输入、输出和审查记录的子目录。 */
    public static final String LLM_DIRECTORY_NAME = "llm";

    /** LLM 工作流使用的运行报告文件名。 */
    public static final String FILE_NAME = "run-report.json";

    private RunReportWriter() {
    }

    /**
     * 写入一个案例的运行报告。
     *
     * @param outputDirectory 当前案例的排样结果目录；报告写入其 {@code llm} 子目录
     * @param caseName 案例名称
     * @param result 最终排样结果
     * @param inputWorkpieceCount 输入的可排样工件总数
     * @param placedWorkpieceCount 最终写入排样结果的工件总数
     * @return 已写入的报告路径
     * @throws IOException 当报告无法写入时抛出
     */
    public static Path write(Path outputDirectory,
                             String caseName,
                             ExecutionResult result,
                             int inputWorkpieceCount,
                             int placedWorkpieceCount) throws IOException {
        if (outputDirectory == null) {
            throw new IllegalArgumentException("运行报告输出目录不能为空。");
        }
        if (result == null) {
            throw new IllegalArgumentException("运行报告需要有效的排样结果。");
        }

        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", "1.1");
        report.addProperty("caseName", caseName == null || caseName.isBlank() ? "unknown" : caseName);
        report.addProperty("priorityBoardCount", Math.max(0, result.priorityBoardCount));
        report.addProperty("ordinaryBoardCount", Math.max(0, result.ordinaryBoardCount));
        report.addProperty("boardCount", solutionCount(result));
        report.addProperty("equivalentContainerCount", result.equivalentContainerCount);
        report.addProperty("actualAverageUtilization", result.actualAverageUtilization);
        report.addProperty("inputWorkpieceCount", Math.max(0, inputWorkpieceCount));
        report.addProperty("placedWorkpieceCount", Math.max(0, placedWorkpieceCount));
        report.addProperty("unplacedWorkpieceCount", countUnplacedWorkpieces(result));

        report.add("timingMs", timing(result));
        report.add("repackMonitoring", repackMonitoring(result));
        report.add("verification", verification(result, inputWorkpieceCount, placedWorkpieceCount));

        Path llmDirectory = outputDirectory.resolve(LLM_DIRECTORY_NAME);
        Files.createDirectories(llmDirectory);
        Path reportPath = llmDirectory.resolve(FILE_NAME);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
        Files.writeString(reportPath, json, StandardCharsets.UTF_8);
        return reportPath;
    }

    /** 将各排样阶段已经记录的实际耗时写入报告。 */
    private static JsonObject timing(ExecutionResult result) {
        JsonObject timing = new JsonObject();
        timing.addProperty("prioritySolve", nonNegative(result.prioritySolveTimeMs));
        timing.addProperty("priorityOptimize", nonNegative(result.priorityOptimizeTimeMs));
        timing.addProperty("ordinaryInsertion", nonNegative(result.ordinaryInsertionTimeMs));
        timing.addProperty("ordinarySolve", nonNegative(result.ordinarySolveTimeMs));
        timing.addProperty("ordinaryOptimize", nonNegative(result.ordinaryOptimizeTimeMs));
        timing.addProperty("initialPacking", nonNegative(result.initialPackingTimeMs));
        timing.addProperty("totalOptimization", nonNegative(result.totalSolveTimeMs));
        timing.addProperty("totalPacking", nonNegative(result.totalPackingTimeMs));
        return timing;
    }

    /**
     * 写入优先件和普通件全局重排的轻量统计，便于定位耗时是否来自候选数量、
     * 重排尝试次数或时间上限。所有字段均在求解过程中只读采集。
     */
    private static JsonObject repackMonitoring(ExecutionResult result) {
        JsonObject monitoring = new JsonObject();
        monitoring.add("priority", repackStatistics(result.priorityRepackStatistics));
        monitoring.add("ordinary", repackStatistics(result.ordinaryRepackStatistics));
        return monitoring;
    }

    private static JsonObject repackStatistics(RepackStatistics statistics) {
        RepackStatistics value = statistics == null
                ? RepackStatistics.notRun("not_recorded") : statistics;
        JsonObject report = new JsonObject();
        report.addProperty("executed", value.executed);
        report.addProperty("stopReason", value.stopReason);
        report.addProperty("requestedTimeLimitMs", nonNegative(value.requestedTimeLimitMs));
        report.addProperty("elapsedTimeMs", nonNegative(value.elapsedTimeMs));
        report.addProperty("unusedTimeLimitMs", Math.max(0L,
                value.requestedTimeLimitMs - value.elapsedTimeMs));
        report.addProperty("boardCountBefore", Math.max(0, value.boardCountBefore));
        report.addProperty("boardCountAfter", Math.max(0, value.boardCountAfter));
        report.addProperty("outerLoopIterations", nonNegative(value.outerLoopIterations));
        report.addProperty("noImprovementSweeps", Math.max(0, value.noImprovementSweeps));
        report.addProperty("candidateBoardAttempts", nonNegative(value.candidateBoardAttempts));
        report.addProperty("pairCandidatesGenerated", nonNegative(value.pairCandidatesGenerated));
        report.addProperty("pairRepackAttempts", nonNegative(value.pairRepackAttempts));
        report.addProperty("successfulPairRepackAttempts", nonNegative(value.successfulPairRepackAttempts));
        report.addProperty("boardReductions", nonNegative(value.boardReductions));
        report.addProperty("timeLimitReached", value.timeLimitReached);
        return report;
    }

    /**
     * 复用排样结果文件原有的校验口径：边界、矩形重叠、工件数与板材快照数。
     */
    private static JsonObject verification(ExecutionResult result,
                                           int inputWorkpieceCount,
                                           int placedWorkpieceCount) {
        int outOfBoundsCount = 0;
        int overlapCount = 0;

        if (result.solutions != null) {
            for (Solution solution : result.solutions) {
                if (solution == null || solution.getPlacedCuboid() == null) {
                    continue;
                }
                for (PlacedCuboid placed : solution.getPlacedCuboid()) {
                    if (isOutOfBounds(solution, placed)) {
                        outOfBoundsCount++;
                    }
                }
                overlapCount += countOverlaps(solution);
            }
        }

        int pieceCountMismatch = Math.abs(Math.max(0, inputWorkpieceCount)
                - Math.max(0, placedWorkpieceCount));
        int boardStateMismatch = Math.abs(solutionCount(result) - boardStateCount(result));

        JsonObject verification = new JsonObject();
        verification.addProperty("outOfBoundsCount", outOfBoundsCount);
        verification.addProperty("overlapCount", overlapCount);
        verification.addProperty("pieceCountMismatch", pieceCountMismatch);
        verification.addProperty("boardStateMismatch", boardStateMismatch);
        verification.addProperty("passed", outOfBoundsCount == 0
                && overlapCount == 0
                && pieceCountMismatch == 0
                && boardStateMismatch == 0);
        return verification;
    }

    private static boolean isOutOfBounds(Solution solution, PlacedCuboid placed) {
        if (placed == null || solution.getInst() == null) {
            return true;
        }
        return placed.x < 0
                || placed.y < 0
                || placed.x + placed.length > solution.getInst().length
                || placed.y + placed.width > solution.getInst().width;
    }

    /** 统计一张板材内相交的矩形对数；仅共享边界不算重叠。 */
    private static int countOverlaps(Solution solution) {
        int overlaps = 0;
        for (int first = 0; first < solution.getPlacedCuboid().size(); first++) {
            PlacedCuboid left = solution.getPlacedCuboid().get(first);
            if (left == null) {
                continue;
            }
            for (int second = first + 1; second < solution.getPlacedCuboid().size(); second++) {
                PlacedCuboid right = solution.getPlacedCuboid().get(second);
                if (right != null && rectanglesOverlap(left, right)) {
                    overlaps++;
                }
            }
        }
        return overlaps;
    }

    private static boolean rectanglesOverlap(PlacedCuboid first, PlacedCuboid second) {
        return first.x < second.x + second.length
                && first.x + first.length > second.x
                && first.y < second.y + second.width
                && first.y + first.width > second.y;
    }

    private static int countUnplacedWorkpieces(ExecutionResult result) {
        if (result.unplacedCounts == null) {
            return result.unplacedBoxes == null ? 0 : result.unplacedBoxes.size();
        }
        int count = 0;
        for (int unplacedCount : result.unplacedCounts) {
            count += Math.max(0, unplacedCount);
        }
        return count;
    }

    private static int solutionCount(ExecutionResult result) {
        return result.solutions == null ? 0 : result.solutions.size();
    }

    private static int boardStateCount(ExecutionResult result) {
        return result.boardStates == null ? 0 : result.boardStates.size();
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }
}
