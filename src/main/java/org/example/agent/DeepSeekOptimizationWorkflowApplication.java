package org.example.agent;

import com.google.gson.JsonObject;

import java.nio.file.Path;

/**
 * LLM 优化闭环的主入口。
 *
 * <p>当前阶段先完成“运行结果 -> 单一决策”的入口。后续会在同一入口中接入代码修改、
 * 确定性验证和下一轮运行，避免为每个环节维护独立的启动程序。</p>
 */
public class DeepSeekOptimizationWorkflowApplication {

    /** 当前测试案例的输出目录。切换案例时只需要修改这一处。 */
    private static final Path CASE_OUTPUT_DIRECTORY = Path.of("data", "BA01_Packing", "Cabinet1");

    /** 排样程序和 LLM 流程共用的案例专属目录。 */
    private static final Path LLM_DIRECTORY = CASE_OUTPUT_DIRECTORY.resolve("llm");
    private static final Path RUN_REPORT_PATH = LLM_DIRECTORY.resolve("run-report.json");
    private static final Path FIRST_ITERATION_DIRECTORY = LLM_DIRECTORY.resolve("iteration-01");
    private static final Path DECISION_PATH = FIRST_ITERATION_DIRECTORY.resolve("decision.json");

    private DeepSeekOptimizationWorkflowApplication() {
    }

    public static void main(String[] args) throws Exception {
        JsonObject runReport = AgentJsonFiles.readObject(RUN_REPORT_PATH, "运行报告");

        DeepSeekClient client = new DeepSeekClient(DeepSeekConfig.fromCode());
        OptimizationDecisionAgent decisionAgent = new OptimizationDecisionAgent(client);
        JsonObject decision = decisionAgent.decide(runReport, null);

        AgentJsonFiles.writeObject(DECISION_PATH, decision);
        System.out.println("已生成优化决策：" + DECISION_PATH.toAbsolutePath());
        System.out.println("决策状态：" + decision.get("status").getAsString());
        System.out.println("本次不会修改排样代码；代码修改和验证阶段将在此入口的下一步接入。");
    }
}
