package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.example.beamsearch.application.LoadingTestRun;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.PrintStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 不使用 LLM 的代码验证器：先编译全部生产源码，再重跑指定案例并比较运行报告。
 */
final class DeterministicCodeVerifier {

    /** 利用率比较的浮点容差，避免显示精度造成误判。 */
    private static final double UTILIZATION_TOLERANCE = 0.0001d;
    /** 解质量相同才比较时间；至少缩短该比例才认为存在稳定的时间收益。 */
    private static final double MIN_RELATIVE_TIME_IMPROVEMENT = 0.03d;

    private final Path projectRoot;

    DeterministicCodeVerifier(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /**
     * 执行完整验证。任意编译、运行或质量校验失败都会在 JSON 中说明原因。
     */
    JsonObject verify(JsonObject baselineReport,
                      Path iterationDirectory,
                      Path materialFile,
                      Path workpieceFile) throws IOException {
        JsonObject report = new JsonObject();
        report.addProperty("validator", "DeterministicCodeVerifier");

        JsonObject compilation = compileProductionSources(iterationDirectory.resolve("compiled-classes"));
        report.add("compilation", compilation);
        if (!compilation.get("passed").getAsBoolean()) {
            report.addProperty("passed", false);
            report.addProperty("failureReason", "生产源码编译失败，未执行案例重跑。");
            return report;
        }

        JsonObject candidateRun = runCandidate(materialFile, workpieceFile,
                iterationDirectory.resolve("candidate-output"));
        report.add("candidateRun", candidateRun);
        if (!candidateRun.get("passed").getAsBoolean()) {
            report.addProperty("passed", false);
            report.addProperty("failureReason", "候选代码无法完成排样案例运行。");
            return report;
        }

        JsonObject candidateReport = candidateRun.getAsJsonObject("runReport");
        JsonObject qualityChecks = compareReports(baselineReport, candidateReport);
        report.add("qualityChecks", qualityChecks);
        boolean passed = qualityChecks.get("safetyPassed").getAsBoolean()
                && qualityChecks.get("effectivenessPassed").getAsBoolean();
        report.addProperty("passed", passed);
        if (!passed) {
            report.addProperty("failureReason", "候选结果没有通过可行性保护或未产生可衡量的优化收益。");
        }
        return report;
    }

    /** 使用当前 JVM 的 Java 编译器编译全部主源码，不依赖 LLM 判断编译结果。 */
    private JsonObject compileProductionSources(Path outputDirectory) throws IOException {
        JsonObject result = new JsonObject();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            result.addProperty("passed", false);
            result.addProperty("message", "当前 Java 运行环境没有编译器，请使用 JDK 17 运行工作流。");
            return result;
        }

        List<Path> sourceFiles = listJavaFiles(projectRoot.resolve("src/main/java"));
        if (sourceFiles.isEmpty()) {
            result.addProperty("passed", false);
            result.addProperty("message", "未找到 src/main/java 下的 Java 源文件。");
            return result;
        }

        Files.createDirectories(outputDirectory);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            List<String> options = List.of(
                    "--release", "17",
                    "-encoding", "UTF-8",
                    "-classpath", System.getProperty("java.class.path", ""),
                    "-d", outputDirectory.toString());
            boolean passed = Boolean.TRUE.equals(compiler.getTask(
                    null, fileManager, diagnostics, options, null, units).call());
            result.addProperty("passed", passed);
            result.addProperty("sourceFileCount", sourceFiles.size());
        }

        result.add("diagnostics", diagnosticsToJson(diagnostics));
        return result;
    }

    /** 使用现有 bridge 输入重跑 BeamSearch，候选输出不会覆盖原案例输出。 */
    private JsonObject runCandidate(Path materialFile,
                                    Path workpieceFile,
                                    Path candidateOutputDirectory) {
        JsonObject result = new JsonObject();
        if (!Files.isRegularFile(materialFile) || !Files.isRegularFile(workpieceFile)) {
            result.addProperty("passed", false);
            result.addProperty("message", "缺少案例 bridge 输入文件，无法重跑排样。");
            result.addProperty("materialFile", materialFile.toAbsolutePath().toString());
            result.addProperty("workpieceFile", workpieceFile.toAbsolutePath().toString());
            return result;
        }

        try {
            Files.createDirectories(candidateOutputDirectory);
            PrintStream originalSystemOut = System.out;
            try {
                LoadingTestRun.runWithImprove(
                        materialFile.toString(),
                        workpieceFile.toString(),
                        candidateOutputDirectory.toString());
            } finally {
                // LoadingTestRun 会临时重定向输出；运行异常时也必须还原控制台。
                System.setOut(originalSystemOut);
            }
            Path reportPath = candidateOutputDirectory.resolve("llm").resolve("run-report.json");
            JsonObject runReport = AgentJsonFiles.readObject(reportPath, "候选排样运行报告");
            result.addProperty("passed", true);
            result.addProperty("runReportPath", reportPath.toAbsolutePath().toString());
            result.add("runReport", runReport);
        } catch (Exception exception) {
            result.addProperty("passed", false);
            result.addProperty("errorType", exception.getClass().getSimpleName());
            result.addProperty("message", safeMessage(exception));
        }
        return result;
    }

    /**
     * 按排样目标的字典序比较基线与候选。
     *
     * <p>优先级依次为 Sp（优先件板数）、So（普通件板数）、N（总板数）、平均利用率、
     * 时间。候选必须可行，且必须在解质量上严格更好；只有解质量完全相同时，时间缩短
     * 至少 3% 才能作为有效改进。</p>
     */
    private JsonObject compareReports(JsonObject baseline, JsonObject candidate) {
        JsonObject result = new JsonObject();
        JsonArray failures = new JsonArray();

        boolean candidateVerificationPassed = booleanValue(candidate, "verification", "passed");
        if (!candidateVerificationPassed) {
            failures.add("候选运行报告的 verification.passed 不是 true。");
        }

        int baselineUnplaced = intValue(baseline, "unplacedWorkpieceCount");
        int candidateUnplaced = intValue(candidate, "unplacedWorkpieceCount");
        if (candidateUnplaced > baselineUnplaced) {
            failures.add("未排工件数从 " + baselineUnplaced + " 增加到 " + candidateUnplaced + "。");
        }

        int baselinePriorityBoards = intValue(baseline, "priorityBoardCount");
        int candidatePriorityBoards = intValue(candidate, "priorityBoardCount");
        int baselineOrdinaryBoards = intValue(baseline, "ordinaryBoardCount");
        int candidateOrdinaryBoards = intValue(candidate, "ordinaryBoardCount");
        int baselineBoards = intValue(baseline, "boardCount");
        int candidateBoards = intValue(candidate, "boardCount");
        double baselineUtilization = doubleValue(baseline, "actualAverageUtilization");
        double candidateUtilization = doubleValue(candidate, "actualAverageUtilization");

        int qualityComparison = compareQuality(
                baselinePriorityBoards, candidatePriorityBoards,
                baselineOrdinaryBoards, candidateOrdinaryBoards,
                baselineBoards, candidateBoards,
                baselineUtilization, candidateUtilization);
        if (qualityComparison > 0) {
            failures.add("候选解按 Sp、So、N、平均利用率的优先级低于基线。");
        }

        long baselineTime = timingValue(baseline, "totalPacking");
        long candidateTime = timingValue(candidate, "totalPacking");
        boolean timeImproved = baselineTime > 0
                && candidateTime <= Math.floor(baselineTime * (1.0d - MIN_RELATIVE_TIME_IMPROVEMENT));
        boolean effectivenessPassed = qualityComparison < 0
                || (qualityComparison == 0 && timeImproved);

        result.addProperty("baselinePriorityBoardCount", baselinePriorityBoards);
        result.addProperty("candidatePriorityBoardCount", candidatePriorityBoards);
        result.addProperty("baselineOrdinaryBoardCount", baselineOrdinaryBoards);
        result.addProperty("candidateOrdinaryBoardCount", candidateOrdinaryBoards);
        result.addProperty("baselineBoardCount", baselineBoards);
        result.addProperty("candidateBoardCount", candidateBoards);
        result.addProperty("baselineUnplacedWorkpieceCount", baselineUnplaced);
        result.addProperty("candidateUnplacedWorkpieceCount", candidateUnplaced);
        result.addProperty("baselineActualAverageUtilization", baselineUtilization);
        result.addProperty("candidateActualAverageUtilization", candidateUtilization);
        result.addProperty("baselineTotalPackingTimeMs", baselineTime);
        result.addProperty("candidateTotalPackingTimeMs", candidateTime);
        result.addProperty("qualityComparison", comparisonName(qualityComparison));
        result.addProperty("timeImprovedAtLeastThreePercent", timeImproved);
        result.add("failures", failures);
        result.addProperty("safetyPassed", failures.isEmpty());
        result.addProperty("effectivenessPassed", effectivenessPassed);
        result.addProperty("passed", failures.isEmpty() && effectivenessPassed);
        return result;
    }

    /**
     * @return -1 表示候选质量更好，0 表示质量相同，1 表示候选质量更差。
     */
    private int compareQuality(int baselineSp,
                               int candidateSp,
                               int baselineSo,
                               int candidateSo,
                               int baselineN,
                               int candidateN,
                               double baselineUtilization,
                               double candidateUtilization) {
        int comparison = Integer.compare(candidateSp, baselineSp);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(candidateSo, baselineSo);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(candidateN, baselineN);
        if (comparison != 0) {
            return comparison;
        }
        if (candidateUtilization > baselineUtilization + UTILIZATION_TOLERANCE) {
            return -1;
        }
        if (candidateUtilization + UTILIZATION_TOLERANCE < baselineUtilization) {
            return 1;
        }
        return 0;
    }

    private String comparisonName(int comparison) {
        return comparison < 0 ? "better" : comparison > 0 ? "worse" : "equal";
    }

    private List<Path> listJavaFiles(Path sourceDirectory) throws IOException {
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private JsonArray diagnosticsToJson(DiagnosticCollector<JavaFileObject> diagnostics) {
        JsonArray result = new JsonArray();
        int limit = Math.min(20, diagnostics.getDiagnostics().size());
        for (int index = 0; index < limit; index++) {
            Diagnostic<? extends JavaFileObject> diagnostic = diagnostics.getDiagnostics().get(index);
            JsonObject item = new JsonObject();
            item.addProperty("kind", diagnostic.getKind().name());
            item.addProperty("line", diagnostic.getLineNumber());
            item.addProperty("message", diagnostic.getMessage(Locale.ROOT));
            if (diagnostic.getSource() != null) {
                item.addProperty("source", diagnostic.getSource().getName());
            }
            result.add(item);
        }
        return result;
    }

    private boolean booleanValue(JsonObject object, String childName, String valueName) {
        JsonObject child = object.getAsJsonObject(childName);
        return child != null && child.has(valueName) && child.get(valueName).getAsBoolean();
    }

    private int intValue(JsonObject object, String name) {
        return object.has(name) ? Math.max(0, object.get(name).getAsInt()) : 0;
    }

    private double doubleValue(JsonObject object, String name) {
        return object.has(name) ? object.get(name).getAsDouble() : 0d;
    }

    private long timingValue(JsonObject report, String name) {
        JsonObject timing = report.getAsJsonObject("timingMs");
        return timing != null && timing.has(name) ? Math.max(0L, timing.get(name).getAsLong()) : 0L;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "未提供异常信息。" : message;
    }
}
