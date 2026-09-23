package org.example.agent;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LLM 优化闭环的主入口。
 *
 * <p>入口按“运行结果 -> 决策 -> 代码修改 -> 确定性验证”执行。代码验证失败时，
 * 只把错误反馈给代码修改 Agent，最多尝试三次；每次失败都会恢复本轮源码备份。</p>
 */
public class DeepSeekOptimizationWorkflowApplication {

    /** 当前测试案例的输出目录。切换案例时只需要修改这一处。 */
    private static final Path CASE_OUTPUT_DIRECTORY = Path.of("data", "BA03_Packing", "Cabinet1");

    /** 排样程序和 LLM 流程共用的案例专属目录。 */
    private static final Path LLM_DIRECTORY = CASE_OUTPUT_DIRECTORY.resolve("llm");
    private static final Path RUN_REPORT_PATH = LLM_DIRECTORY.resolve("run-report.json");
    private static final Path FINAL_RESULT_PATH = LLM_DIRECTORY.resolve("final-result.json");

    /** 当前案例已有的 bridge 输入；候选代码只重跑排样，不重复计算 NFP。 */
    private static final Path BRIDGE_CASE_DIRECTORY = CASE_OUTPUT_DIRECTORY.getParent()
            .resolve("material")
            .resolve(CASE_OUTPUT_DIRECTORY.getFileName().toString());
    private static final Path MATERIAL_FILE = BRIDGE_CASE_DIRECTORY.resolve("material.csv");
    private static final Path WORKPIECE_FILE = BRIDGE_CASE_DIRECTORY.resolve("workpiece");

    /** 限制失败修复次数，避免出现无限模型调用。 */
    private static final int MAX_MODIFICATION_ATTEMPTS = 3;

    private DeepSeekOptimizationWorkflowApplication() {
    }

    public static void main(String[] args) throws Exception {
        JsonObject runReport = readLatestRunReport();
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        OptimizationTargetCatalog targetCatalog = OptimizationTargetCatalog.create(projectRoot);
        SourceContextReader sourceContextReader = new SourceContextReader(
                projectRoot.resolve("src/main/java"));

        DeepSeekClient client = new DeepSeekClient(DeepSeekConfig.fromCode());
        OptimizationDecisionAgent decisionAgent = new OptimizationDecisionAgent(client);
        // 决策前自动提供已登记入口的真实方法，避免模型再请求人工粘贴源码。
        String decisionSourceContext = sourceContextReader.readForCodeTargets(targetCatalog.asJson());
        JsonObject decision = decisionAgent.decide(
                runReport, null, targetCatalog, decisionSourceContext);
        int firstIteration = nextIterationNumber();
        Path firstIterationDirectory = iterationDirectory(firstIteration);
        AgentJsonFiles.writeObject(firstIterationDirectory.resolve("decision.json"), decision);

        String decisionStatus = decision.get("status").getAsString();
        System.out.println("已生成优化决策：" + firstIterationDirectory.resolve("decision.json").toAbsolutePath());
        System.out.println("决策状态：" + decisionStatus);
        if (!"ready_for_implementation".equals(decisionStatus)) {
            writeFinalResult("decision_not_ready", 0, decision, null, null, null);
            System.out.println("当前决策不允许修改代码，工作流已结束。");
            return;
        }

        CodeModificationAgent modificationAgent = new CodeModificationAgent(client);
        CodeChangeApplier changeApplier = new CodeChangeApplier(projectRoot);
        DeterministicCodeVerifier verifier = new DeterministicCodeVerifier(projectRoot);

        JsonObject previousValidation = null;
        for (int attempt = 1; attempt <= MAX_MODIFICATION_ATTEMPTS; attempt++) {
            int iteration = firstIteration + attempt - 1;
            Path iterationDirectory = iterationDirectory(iteration);
            Files.createDirectories(iterationDirectory);
            String sourceContext = sourceContextReader.readForDecision(decision);
            JsonObject changeSet = modificationAgent.createChanges(
                    decision, sourceContext, previousValidation);
            AgentJsonFiles.writeObject(iterationDirectory.resolve("code-changes.json"), changeSet);

            String changeStatus = changeSet.get("status").getAsString();
            if (!"ready_to_apply".equals(changeStatus)) {
                JsonObject validation = stoppedValidation(changeStatus, changeSet);
                AgentJsonFiles.writeObject(iterationDirectory.resolve("validation.json"), validation);
                writeFinalResult("code_change_not_ready", iteration, decision, changeSet, validation, null);
                System.out.println("代码修改 Agent 未给出可安全应用的修改，工作流已结束。");
                return;
            }

            CodeChangeApplier.ApplicationResult applicationResult;
            try {
                applicationResult = changeApplier.apply(decision, changeSet, iterationDirectory);
            } catch (Exception exception) {
                JsonObject validation = failedValidation("apply", exception);
                AgentJsonFiles.writeObject(iterationDirectory.resolve("validation.json"), validation);
                previousValidation = validation;
                continue;
            }

            JsonObject validation;
            try {
                ModificationReportWriter.write(
                        iterationDirectory.resolve("modification-report.md"),
                        iteration,
                        decision,
                        changeSet,
                        applicationResult);
                validation = verifier.verify(runReport, iterationDirectory, MATERIAL_FILE, WORKPIECE_FILE);
            } catch (Exception exception) {
                validation = failedValidation("verify", exception);
            }

            boolean passed = validation.has("passed") && validation.get("passed").getAsBoolean();
            if (!passed) {
                changeApplier.restore(applicationResult);
                validation.addProperty("sourceRestored", true);
            }
            ModificationReportWriter.appendValidationResult(
                    iterationDirectory.resolve("modification-report.md"), passed);
            AgentJsonFiles.writeObject(iterationDirectory.resolve("validation.json"), validation);

            if (passed) {
                JsonObject resultAnalysis = analyzeCandidateResult(
                        decisionAgent, validation, targetCatalog, sourceContextReader);
                AgentJsonFiles.writeObject(iterationDirectory.resolve("result-analysis.json"), resultAnalysis);
                writeFinalResult("applied_and_validated", iteration,
                        decision, changeSet, validation, resultAnalysis);
                System.out.println("代码修改和确定性验证均已通过。");
                System.out.println("修改说明：" + iterationDirectory.resolve("modification-report.md").toAbsolutePath());
                System.out.println("新结果分析：" + iterationDirectory.resolve("result-analysis.json").toAbsolutePath());
                return;
            }

            previousValidation = validation;
            System.out.println("第 " + iteration + " 轮验证失败，已恢复源文件，将反馈给代码修改 Agent。");
        }

        writeFinalResult("modification_attempts_exhausted",
                firstIteration + MAX_MODIFICATION_ATTEMPTS - 1,
                decision, null, previousValidation, null);
        System.out.println("三轮代码修改均未通过验证，工作流已停止且源码已恢复。");
    }

    /** 返回当前尝试对应的独立目录，所有 JSON、备份和候选结果均保存在这里。 */
    private static Path iterationDirectory(int attempt) {
        return LLM_DIRECTORY.resolve("iteration-%02d".formatted(attempt));
    }

    /** 找到未使用的迭代目录，保留过去的决策、修改说明和验证证据。 */
    private static int nextIterationNumber() {
        int iteration = 1;
        while (Files.exists(iterationDirectory(iteration))) {
            iteration++;
        }
        return iteration;
    }

    /**
     * 优先使用最近一次通过验证的候选运行报告，使下一次启动能够从改进后的结果继续分析。
     */
    private static JsonObject readLatestRunReport() throws Exception {
        if (Files.isRegularFile(FINAL_RESULT_PATH)) {
            JsonObject finalResult = AgentJsonFiles.readObject(FINAL_RESULT_PATH, "最终结果");
            JsonObject validation = finalResult.getAsJsonObject("validation");
            JsonObject candidateRun = validation == null ? null : validation.getAsJsonObject("candidateRun");
            JsonObject candidateReport = candidateRun == null ? null : candidateRun.getAsJsonObject("runReport");
            if ("applied_and_validated".equals(stringValue(finalResult, "status"))
                    && candidateReport != null) {
                return candidateReport;
            }
        }
        return AgentJsonFiles.readObject(RUN_REPORT_PATH, "运行报告");
    }

    /** 成功运行后重新分析候选结果，但不在当前轮继续自动修改，防止无限优化。 */
    private static JsonObject analyzeCandidateResult(OptimizationDecisionAgent decisionAgent,
                                                      JsonObject validation,
                                                      OptimizationTargetCatalog targetCatalog,
                                                      SourceContextReader sourceContextReader) {
        try {
            JsonObject candidateRun = validation.getAsJsonObject("candidateRun");
            JsonObject candidateReport = candidateRun == null ? null : candidateRun.getAsJsonObject("runReport");
            if (candidateReport == null) {
                return failedValidation("result_analysis",
                        new IllegalArgumentException("验证通过但未找到候选运行报告。"));
            }
            String sourceContext = sourceContextReader.readForCodeTargets(targetCatalog.asJson());
            return decisionAgent.decide(candidateReport, validation, targetCatalog, sourceContext);
        } catch (Exception exception) {
            // 代码已经验证通过；分析调用失败不能回滚已通过的算法修改。
            return failedValidation("result_analysis", exception);
        }
    }

    /** 将终止或成功状态集中写入案例目录，方便不打开多个 JSON 文件也能查看结果。 */
    private static void writeFinalResult(String status,
                                         int completedIteration,
                                         JsonObject decision,
                                         JsonObject changeSet,
                                         JsonObject validation,
                                         JsonObject resultAnalysis) throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        result.addProperty("completedIteration", completedIteration);
        result.addProperty("maxModificationAttempts", MAX_MODIFICATION_ATTEMPTS);
        result.add("decision", decision);
        if (changeSet != null) {
            result.add("codeChanges", changeSet);
        }
        if (validation != null) {
            result.add("validation", validation);
        }
        if (resultAnalysis != null) {
            result.add("resultAnalysis", resultAnalysis);
        }
        AgentJsonFiles.writeObject(FINAL_RESULT_PATH, result);
    }

    /** 将 Agent 主动停止修改的原因写成与失败验证相同的结构。 */
    private static JsonObject stoppedValidation(String changeStatus, JsonObject changeSet) {
        JsonObject result = new JsonObject();
        result.addProperty("passed", false);
        result.addProperty("stage", "code_modification");
        result.addProperty("failureReason", "代码修改 Agent 返回状态：" + changeStatus);
        result.add("agentResponse", changeSet);
        return result;
    }

    /** 将应用或验证阶段的异常转为下一轮修改 Agent 可读取的结构化反馈。 */
    private static JsonObject failedValidation(String stage, Exception exception) {
        JsonObject result = new JsonObject();
        result.addProperty("passed", false);
        result.addProperty("stage", stage);
        result.addProperty("errorType", exception.getClass().getSimpleName());
        String message = exception.getMessage();
        result.addProperty("failureReason", message == null || message.isBlank()
                ? "未提供异常信息。" : message);
        return result;
    }

    private static String stringValue(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString() : "";
    }
}
