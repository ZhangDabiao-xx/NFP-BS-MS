package org.example.application;

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

    private BaselinePackingApplication() {
    }

    public static void main(String[] args) throws IOException {
        switch (args.length) {
            case 0 -> PackingPipeline.run(DEFAULT_CASE_PATH);
            case 1 -> PackingPipeline.run(Path.of(args[0]));
            case 3 -> PackingPipeline.run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.err.println("用法:");
        System.err.println("  BaselinePackingApplication [caseJsonFileOrDirectory]");
        System.err.println("  BaselinePackingApplication <caseJsonFileOrDirectory> <nfpOutputDirectory> <packingOutputDirectory>");
    }
}
