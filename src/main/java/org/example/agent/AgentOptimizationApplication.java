package org.example.agent;

import java.nio.file.Path;

/**
 * Agent 优化启动入口。
 *
 * <p>此入口与 {@code BaselinePackingApplication} 完全分离。{@code nfp} 运行 NFP
 * 候选方案实验；{@code packing} 在已有排样结果上运行代码优化闭环。两种模式都不会覆盖
 * baseline 的 NFP 与排样结果。</p>
 */
public final class AgentOptimizationApplication {
    private AgentOptimizationApplication() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            return;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "nfp" -> runNfpAgent(args);
            case "packing" -> runPackingAgent(args);
            default -> printUsage();
        }
    }

    private static void runNfpAgent(String[] args) throws Exception {
        if (args.length > 3) {
            printUsage();
            return;
        }
        int rounds = args.length == 3 ? Integer.parseInt(args[2]) : NfpAgentExperiment.DEFAULT_MAX_ITERATIONS;
        NfpAgentExperiment.run(Path.of(args[1]), rounds);
    }

    private static void runPackingAgent(String[] args) throws Exception {
        if (args.length != 2) {
            printUsage();
            return;
        }
        PackingAgentWorkflow.run(Path.of(args[1]));
    }

    private static void printUsage() {
        System.err.println("用法:");
        System.err.println("  AgentOptimizationApplication nfp <caseJsonFileOrDirectory> [rounds]");
        System.err.println("  AgentOptimizationApplication packing <casePackingOutputDirectory>");
    }
}
