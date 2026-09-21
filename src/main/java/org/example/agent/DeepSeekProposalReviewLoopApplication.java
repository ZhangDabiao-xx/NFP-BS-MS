package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;

/**
 * DeepSeek 优化工作流的主入口。
 *
 * <p>一次运行依次完成：运行报告分析、第一版方案生成、可行性审查和反馈修订。
 * 审查循环最多三轮；达到上限后写入人工决策文件，绝不让未审查方案进入代码修改阶段。</p>
 */
public final class DeepSeekProposalReviewLoopApplication {

    /** 当前案例的事实依据。 */
    private static final Path RUN_REPORT_PATH =
            Path.of("data", "BA01_Packing", "Cabinet1", "run-report.json");

    /** 结果分析 Agent 自动生成的诊断文件。 */
    private static final Path ANALYSIS_PATH =
            Path.of("data", "BA01_Packing", "Cabinet1", "analysis.json");

    /** 方案生成 Agent 自动生成的第一版候选方案。 */
    private static final Path INITIAL_PROPOSAL_PATH =
            Path.of("data", "BA01_Packing", "Cabinet1", "optimization-proposal.json");

    /** 仅允许审查本项目的 Java 业务源码。 */
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /** 审查与修订的最大轮数，防止模型在相同问题上无限循环。 */
    private static final int MAX_REVIEW_ROUNDS = 3;

    private DeepSeekProposalReviewLoopApplication() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 0) {
            System.out.println("本入口不接收命令行参数；请在类顶部修改案例路径。");
            return;
        }

        JsonObject runReport = AgentJsonFiles.readObject(RUN_REPORT_PATH, "运行报告");
        Path outputDirectory = INITIAL_PROPOSAL_PATH.getParent();
        if (outputDirectory == null) {
            throw new IllegalArgumentException("候选方案必须位于案例结果目录中。");
        }

        DeepSeekClient client = new DeepSeekClient(DeepSeekConfig.fromCode());
        ResultAnalysisAgent analysisAgent = new ResultAnalysisAgent(client);
        OptimizationProposalAgent proposalAgent = new OptimizationProposalAgent(client);
        FeasibilityReviewAgent reviewAgent = new FeasibilityReviewAgent(client);
        SourceContextReader contextReader = new SourceContextReader(SOURCE_ROOT);

        // 每次主工作流都重新分析同一份运行报告，避免使用与当前案例不一致的旧分析文件。
        JsonObject analysis = analysisAgent.analyze(runReport.toString());
        AgentJsonFiles.writeObject(ANALYSIS_PATH, analysis);

        JsonObject currentProposal = proposalAgent.createProposal(runReport, analysis);
        AgentJsonFiles.writeObject(INITIAL_PROPOSAL_PATH, currentProposal);

        for (int round = 1; round <= MAX_REVIEW_ROUNDS; round++) {
            Path reviewPath = outputDirectory.resolve("feasibility-review-v" + round + ".json");
            String sourceContext = contextReader.readForProposal(currentProposal);
            JsonObject review = reviewAgent.review(runReport, currentProposal, sourceContext, round);
            AgentJsonFiles.writeObject(reviewPath, review);

            if (hasFullyApprovedProposal(review)) {
                writeApprovedHandoff(outputDirectory, currentProposal, review, round);
                System.out.println("可行性审查通过: " + reviewPath.toAbsolutePath());
                return;
            }

            if (round == MAX_REVIEW_ROUNDS) {
                writeHumanDecision(outputDirectory, round, reviewPath);
                System.out.println("未获得通过方案，已写入人工决策文件。");
                return;
            }

            currentProposal = proposalAgent.reviseProposal(
                    runReport,
                    currentProposal,
                    review,
                    round + 1);
            Path revisedProposalPath = outputDirectory.resolve("optimization-proposal-v"
                    + (round + 1) + ".json");
            AgentJsonFiles.writeObject(revisedProposalPath, currentProposal);
            System.out.println("第 " + (round + 1) + " 版方案已写入: "
                    + revisedProposalPath.toAbsolutePath());
        }
    }

    /**
     * 只有明确 approved、没有拒绝原因、也不再要求补证据的方案才能进入代码修改阶段。
     * 这样可以防止模型同时写出 approved 与 requiredEvidence 时被过早放行。
     */
    private static boolean hasFullyApprovedProposal(JsonObject review) {
        JsonArray reviews = review.getAsJsonArray("reviews");
        if (reviews == null) {
            return false;
        }
        for (JsonElement element : reviews) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (isFullyApproved(item)) {
                return true;
            }
        }
        return false;
    }

    /** 将完全通过的方案单独交给后续代码修改 Agent，避免混入未通过方案。 */
    private static void writeApprovedHandoff(Path outputDirectory,
                                             JsonObject proposal,
                                             JsonObject review,
                                             int reviewRound) throws IOException {
        JsonObject handoff = new JsonObject();
        handoff.addProperty("status", "approved_for_implementation");
        handoff.addProperty("reviewRound", reviewRound);
        JsonArray approvedProposals = new JsonArray();
        JsonArray proposalItems = proposal.getAsJsonArray("proposals");
        JsonArray reviewItems = review.getAsJsonArray("reviews");

        if (proposalItems != null && reviewItems != null) {
            for (JsonElement proposalItem : proposalItems) {
                if (!proposalItem.isJsonObject()) {
                    continue;
                }
                String proposalId = proposalItem.getAsJsonObject().has("id")
                        ? proposalItem.getAsJsonObject().get("id").getAsString()
                        : "";
                if (isProposalFullyApproved(proposalId, reviewItems)) {
                    approvedProposals.add(proposalItem.deepCopy());
                }
            }
        }
        handoff.add("proposals", approvedProposals);
        AgentJsonFiles.writeObject(outputDirectory.resolve("approved-proposals.json"), handoff);
    }

    private static boolean isProposalFullyApproved(String proposalId, JsonArray reviewItems) {
        for (JsonElement reviewItem : reviewItems) {
            if (!reviewItem.isJsonObject()) {
                continue;
            }
            JsonObject item = reviewItem.getAsJsonObject();
            if (item.has("proposalId")
                    && proposalId.equals(item.get("proposalId").getAsString())
                    && isFullyApproved(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFullyApproved(JsonObject reviewItem) {
        return reviewItem.has("decision")
                && "approved".equals(reviewItem.get("decision").getAsString())
                && isEmptyArray(reviewItem.getAsJsonArray("rejectionReasons"))
                && isEmptyArray(reviewItem.getAsJsonArray("requiredEvidence"));
    }

    private static boolean isEmptyArray(JsonArray values) {
        return values == null || values.isEmpty();
    }

    /** 达到最大轮数时记录确定性的停机原因，交由用户决定是否继续。 */
    private static void writeHumanDecision(Path outputDirectory,
                                           int completedRounds,
                                           Path finalReviewPath) throws IOException {
        JsonObject decision = new JsonObject();
        decision.addProperty("status", "needs_human_decision");
        decision.addProperty("completedReviewRounds", completedRounds);
        decision.addProperty("finalReview", finalReviewPath.getFileName().toString());
        decision.addProperty("message", "连续审查未产生通过方案，未执行任何代码修改。");
        AgentJsonFiles.writeObject(outputDirectory.resolve("needs-human-decision.json"), decision);
    }
}
