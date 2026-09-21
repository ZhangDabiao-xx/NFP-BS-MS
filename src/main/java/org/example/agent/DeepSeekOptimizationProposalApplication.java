package org.example.agent;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 优化方案生成 Agent 的直接运行入口。
 *
 * <p>默认路径和模型配置都写在代码中，直接从 IDE 运行即可；
 * 需要换案例时只修改本类顶部三个路径常量。</p>
 */
public final class DeepSeekOptimizationProposalApplication {

    /** 当前要分析的案例运行报告。 */
    private static final Path RUN_REPORT_PATH =
            Path.of("data", "BA01_Packing", "Cabinet1", "run-report.json");

    /** 结果分析 Agent 已生成的诊断文件。 */
    private static final Path ANALYSIS_PATH =
            Path.of("data", "BA01_Packing", "Cabinet1", "analysis.json");

    /** 本 Agent 生成的待审查方案文件。 */
    private static final Path PROPOSAL_PATH =
            Path.of("data", "BA01_Packing", "Cabinet1", "optimization-proposal.json");

    private DeepSeekOptimizationProposalApplication() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 0) {
            System.out.println("本入口不接收命令行参数；请在类顶部修改案例文件路径。");
            return;
        }

        try {
            JsonObject runReport = readJsonObject(RUN_REPORT_PATH, "运行报告");
            JsonObject analysis = readJsonObject(ANALYSIS_PATH, "分析结果");

            DeepSeekClient client = new DeepSeekClient(DeepSeekConfig.fromCode());
            OptimizationProposalAgent agent = new OptimizationProposalAgent(client);
            JsonObject proposal = agent.createProposal(runReport, analysis);

            Path parent = PROPOSAL_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String formatted = new GsonBuilder().setPrettyPrinting().create().toJson(proposal);
            Files.writeString(PROPOSAL_PATH, formatted, StandardCharsets.UTF_8);
            System.out.println("优化方案已写入: " + PROPOSAL_PATH.toAbsolutePath());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // 对路径、JSON 或模型配置错误给出简短提示，避免直接输出冗长堆栈。
            System.err.println("优化方案 Agent 启动失败: " + exception.getMessage());
            System.exit(2);
        }
    }

    /** 读取并确认输入文件是 JSON 对象，防止把错误文件发送给模型。 */
    private static JsonObject readJsonObject(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(description + "不存在: " + path.toAbsolutePath());
        }
        try {
            JsonElement element = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(description + "必须是 JSON 对象: " + path.toAbsolutePath());
            }
            return element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            throw new IllegalArgumentException(description + "不是合法 JSON: " + path.toAbsolutePath(), exception);
        }
    }
}
