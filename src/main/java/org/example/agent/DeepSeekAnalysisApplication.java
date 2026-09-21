package org.example.agent;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DeepSeek 结果分析 Agent 的最小命令行入口。
 *
 * <p>该入口只调用模型分析已有 JSON 报告，不会运行排样程序，也不会修改项目代码。</p>
 */
public final class DeepSeekAnalysisApplication {

    /**
     * 直接从 IDE 运行时使用的默认报告。
     *
     * <p>排样程序暂未自动生成 Agent 所需的汇总报告，因此先使用项目内示例报告
     * 验证 LLM 接入。后续接入真实报告后，只需要修改这一处路径即可。</p>
     */
    private static final Path DEFAULT_RUN_REPORT_PATH =
            Path.of("data","BA01_Packing", "Cabinet1", "run-report.json");

    /** 直接运行时的默认分析结果保存位置。 */
    private static final Path DEFAULT_ANALYSIS_PATH =
            Path.of("data","BA01_Packing", "Cabinet1","analysis.json");

    private DeepSeekAnalysisApplication() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length > 2) {
            printUsage();
            return;
        }

        try {
            // API Key、接口地址和模型名均由 DeepSeekConfig 从代码常量读取；
            // 命令行参数只允许覆盖报告文件和分析结果文件路径。
            Path inputPath = args.length >= 1 ? Path.of(args[0]) : DEFAULT_RUN_REPORT_PATH;
            Path outputPath = args.length == 2 ? Path.of(args[1]) : DEFAULT_ANALYSIS_PATH;
            DeepSeekConfig config = DeepSeekConfig.fromCode();
            DeepSeekClient client = new DeepSeekClient(config);
            ResultAnalysisAgent agent = new ResultAnalysisAgent(client);

            JsonObject analysis = agent.analyzeFile(inputPath);
            String formatted = new GsonBuilder()
                    .setPrettyPrinting()
                    .create()
                    .toJson(analysis);

            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputPath, formatted, StandardCharsets.UTF_8);
            System.out.println("分析结果已写入: " + outputPath.toAbsolutePath());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // 配置或命令行输入错误时给出简短提示，避免用户需要阅读堆栈定位问题。
            System.err.println("DeepSeek Agent 配置错误: " + exception.getMessage());
            System.exit(2);
        }
    }

    private static void printUsage() {
        System.out.println("用法:");
        System.out.println("  DeepSeekAnalysisApplication [run-report.json] [analysis.json]");
        System.out.println();
        System.out.println("不传参数时，默认分析 " + DEFAULT_RUN_REPORT_PATH
                + "，结果写入 " + DEFAULT_ANALYSIS_PATH + "。");
        System.out.println("请先在 DeepSeekConfig.java 顶部填写 API_KEY、API_URL 和 MODEL。");
    }
}
