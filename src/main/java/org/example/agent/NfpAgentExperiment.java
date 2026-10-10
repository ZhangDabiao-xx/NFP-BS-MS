package org.example.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/** NFP Agent 的有界实验服务：baseline 保持原算法，候选使用局部重建、去重和分档评估。 */
final class NfpAgentExperiment {
    /** 轮次是批量设计与反馈次数，不再意味着重复执行整例 NFP 和长时间排样。 */
    static final int DEFAULT_MAX_ITERATIONS = 4;
    /** false 只测试带明确标记的本地探索种子，适合离线验证。 */
    private static final boolean USE_LLM = true;
    /** 每案例总计最多 6 次快速、2 次中等、1 次最终候选排样，不包含三个同档 baseline。 */
    private static final int MAX_QUICK_CANDIDATES = 6;
    private static final long QUICK_BUDGET_MS = 10_000;
    private static final long MEDIUM_BUDGET_MS = 60_000;
    private static final long FINAL_BUDGET_MS = 600_000;
    /** 完整 baseline 建立后开始计时；初始排样/单次几何操作仅支持协作式停止。 */
    private static final long AGENT_WALL_BUDGET_MS = 25 * 60_000L;
    private static final long NFP_TRIAL_BUDGET_MS = 45_000;

    private NfpAgentExperiment() {
    }

    static void run(Path input, int rounds) throws Exception {
        NfpAgentOptimizer.Settings settings = new NfpAgentOptimizer.Settings(rounds, MAX_QUICK_CANDIDATES,
                QUICK_BUDGET_MS, MEDIUM_BUDGET_MS, FINAL_BUDGET_MS, AGENT_WALL_BUDGET_MS, NFP_TRIAL_BUDGET_MS, USE_LLM);
        if (Files.isRegularFile(input)) {
            runCase(input, settings);
        } else if (Files.isDirectory(input)) {
            // 每案例独立计数/预算，防止把其他案例的完成次数算到当前案例中。
            try (var files = Files.list(input)) {
                for (Path file : files.filter(path -> Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")).sorted().toList()) {
                    runCase(file, settings);
                }
            }
        } else {
            throw new IllegalArgumentException("输入路径不存在: " + input.toAbsolutePath());
        }
    }

    private static void runCase(Path input, NfpAgentOptimizer.Settings settings) throws Exception {
        Path data = input.toAbsolutePath().getParent().getParent();
        String name = input.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path artifacts = data.resolve("BA06_Packing").resolve(name).resolve("llm/nfp-agent");
        Path summary = new NfpAgentOptimizer(settings).run(input, artifacts);
        System.out.println("NFP Agent 实验完成，最终结论: " + summary.toAbsolutePath());
    }
}
