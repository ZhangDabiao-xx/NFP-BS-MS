package org.example.agent;

import org.example.application.IntegratedPackingApplication;

import java.nio.file.Path;

/**
 * Explicit, bounded A/B experiment runner. Ordinary IntegratedPackingApplication runs once;
 * this runner first creates a fixed baseline and then executes distinct profile iterations.
 */
public final class NfpAgentExperimentApplication {
    /**
     * 在 IDE 中直接运行本类时使用的案例。需要切换案例时，只修改这一行。
     * 路径应指向输入 JSON 文件，或只包含 JSON 案例的输入目录；不能指向 BA06_Packing 等输出目录。
     */
    private static final Path DEFAULT_CASE_PATH = Path.of("data", "inputData2", "Cabinet1.json");
    private static final int DEFAULT_MAX_ITERATIONS = 10;

    private NfpAgentExperimentApplication() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 2) {
            System.out.println("用法: NfpAgentExperimentApplication [案例文件或目录] [maxIterations]");
            return;
        }
        Path casePath = args.length >= 1 ? Path.of(args[0]) : DEFAULT_CASE_PATH;
        int maxIterations = args.length == 2 ? Integer.parseInt(args[1]) : DEFAULT_MAX_ITERATIONS;
        if (maxIterations <= 0) throw new IllegalArgumentException("maxIterations 必须为正数。");

        String prior = System.getProperty("nfp.agent.enabled");
        try {
            // Baseline is deliberately separated from trials, so every outcome has a stable comparator.
            System.setProperty("nfp.agent.enabled", "false");
            IntegratedPackingApplication.run(casePath);
            System.setProperty("nfp.agent.enabled", "true");
            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                System.out.println("开始 NFP Agent 实验轮次 " + iteration + "/" + maxIterations);
                IntegratedPackingApplication.run(casePath);
            }
        } finally {
            if (prior == null) System.clearProperty("nfp.agent.enabled"); else System.setProperty("nfp.agent.enabled", prior);
        }
    }
}
