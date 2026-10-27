package org.example.application;

import org.example.beamsearch.application.PackingConvergenceRecorder;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 基准求解启动入口。
 *
 * <p>该入口始终执行固定的 NFP 与优先级排样算法，不读取任何 Agent 配置，也不会产生
 * Agent 实验结果。它是所有优化实验进行效果比较时的唯一基线。</p>
 */
public final class BaselinePackingApplication {
    private static final Path DEFAULT_CASE_PATH = Path.of("data", "inputData3");

    /** 收敛统计开关：只有设为 true 才采样和输出 CSV，不影响纯矩形入口。 */
    private static final boolean ENABLE_CONVERGENCE_STATISTICS = true;
    /** 常规采样步长，单位为毫秒；历史最优刷新、减板及起止点不受步长限制。 */
    private static final long CONVERGENCE_SAMPLE_INTERVAL_MS = 1_000L;
    /** 统计输出根目录，每个案例在其同名子目录内输出优先件和普通件两个 CSV。 */
    private static final Path CONVERGENCE_OUTPUT_DIRECTORY = Path.of("data", "packingConvergence2");

    private BaselinePackingApplication() {
    }

    public static void main(String[] args) throws IOException {
        PackingConvergenceRecorder.Config statistics = new PackingConvergenceRecorder.Config(
                ENABLE_CONVERGENCE_STATISTICS, CONVERGENCE_SAMPLE_INTERVAL_MS, CONVERGENCE_OUTPUT_DIRECTORY);
        switch (args.length) {
            case 0 -> PackingPipeline.run(DEFAULT_CASE_PATH, statistics);
            case 1 -> PackingPipeline.run(Path.of(args[0]), statistics);
            case 3 -> PackingPipeline.run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), statistics);
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.err.println("用法:");
        System.err.println("  BaselinePackingApplication [caseJsonFileOrDirectory]");
        System.err.println("  BaselinePackingApplication <caseJsonFileOrDirectory> <nfpOutputDirectory> <packingOutputDirectory>");
    }
}
