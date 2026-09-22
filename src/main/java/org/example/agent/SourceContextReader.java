package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 从候选方案中提取目标 Java 文件的有限代码片段，供可行性审查使用。
 *
 * <p>只允许读取 {@code src/main/java} 和 {@code src/test/java} 内、且方案明确列出的
 * Java 文件，防止方案文本诱导 Agent 读取无关文件或敏感配置。</p>
 */
final class SourceContextReader {

    private static final int MAX_FILE_COUNT = 6;
    private static final int MAX_TOTAL_CHARS = 24_000;
    private static final int MAX_CHARS_PER_FILE = 12_000;

    private final Path sourceRoot;
    private final Path projectRoot;
    private final Path testSourceRoot;

    SourceContextReader(Path sourceRoot) {
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        // sourceRoot 是 <project>/src/main/java，向上三级得到项目根目录。
        this.projectRoot = this.sourceRoot.getParent().getParent().getParent();
        this.testSourceRoot = projectRoot.resolve("src").resolve("test").resolve("java");
    }

    /**
     * 读取候选方案涉及的代码片段。
     *
     * @return 带相对路径标题的代码文本，供模型只读审查
     */
    String readForProposal(JsonObject proposal) throws IOException {
        Map<String, Set<String>> targets = collectTargets(proposal);
        return readTargets(targets, "候选方案未声明 targetFiles，无法进行代码审查。");
    }

    /**
     * 读取单一决策方案涉及的代码片段，供代码修改 Agent 使用。
     */
    String readForDecision(JsonObject decision) throws IOException {
        JsonObject selectedPlan = decision.getAsJsonObject("selectedPlan");
        if (selectedPlan == null) {
            throw new IllegalArgumentException("决策未包含 selectedPlan，无法准备代码上下文。");
        }
        Map<String, Set<String>> targets = collectSingleTarget(selectedPlan);
        return readTargets(targets, "selectedPlan 未声明 targetFiles，无法准备代码上下文。");
    }

    private String readTargets(Map<String, Set<String>> targets, String emptyMessage) throws IOException {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(emptyMessage);
        }

        StringBuilder context = new StringBuilder();
        int remainingChars = MAX_TOTAL_CHARS;
        int fileCount = 0;
        for (Map.Entry<String, Set<String>> target : targets.entrySet()) {
            if (fileCount >= MAX_FILE_COUNT || remainingChars <= 0) {
                break;
            }
            Path file = resolveJavaSource(target.getKey());
            String snippet = Files.isRegularFile(file)
                    ? extractSnippet(file, target.getValue(), remainingChars)
                    : "[该目标是计划新建的 Java 文件，当前没有可供审查的源码。]";
            context.append("\n--- ").append(projectRoot.relativize(file)).append(" ---\n");
            context.append(snippet).append('\n');
            remainingChars -= snippet.length();
            fileCount++;
        }

        if (fileCount == 0) {
            throw new IllegalArgumentException("候选方案没有可读取的目标 Java 文件。");
        }
        return context.toString();
    }

    private Map<String, Set<String>> collectTargets(JsonObject proposal) {
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        JsonArray proposals = proposal.getAsJsonArray("proposals");
        if (proposals == null) {
            return targets;
        }

        for (JsonElement element : proposals) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            JsonArray files = item.getAsJsonArray("targetFiles");
            if (files == null) {
                continue;
            }
            Set<String> methods = stringValues(item.getAsJsonArray("targetMethods"));
            for (JsonElement file : files) {
                if (file.isJsonPrimitive()) {
                    targets.computeIfAbsent(file.getAsString(), ignored -> new LinkedHashSet<>())
                            .addAll(methods);
                }
            }
        }
        return targets;
    }

    /** 将一个 selectedPlan 转换为与旧方案相同的目标文件结构。 */
    private Map<String, Set<String>> collectSingleTarget(JsonObject selectedPlan) {
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        JsonArray files = selectedPlan.getAsJsonArray("targetFiles");
        if (files == null) {
            return targets;
        }
        Set<String> methods = stringValues(selectedPlan.getAsJsonArray("targetMethods"));
        for (JsonElement file : files) {
            if (file.isJsonPrimitive()) {
                targets.computeIfAbsent(file.getAsString(), ignored -> new LinkedHashSet<>())
                        .addAll(methods);
            }
        }
        return targets;
    }

    private Set<String> stringValues(JsonArray values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) {
            return result;
        }
        for (JsonElement value : values) {
            if (value.isJsonPrimitive()) {
                result.add(value.getAsString());
            }
        }
        return result;
    }

    private Path resolveJavaSource(String declaredPath) {
        if (declaredPath == null || !declaredPath.endsWith(".java")) {
            throw new IllegalArgumentException("审查目标必须是 Java 源文件: " + declaredPath);
        }
        Path relative = Path.of(declaredPath).normalize();
        Path resolved = projectRoot.resolve(relative).normalize();
        boolean allowedSource = resolved.startsWith(sourceRoot) || resolved.startsWith(testSourceRoot);
        if (!allowedSource) {
            throw new IllegalArgumentException("审查目标不在允许的源码目录中: " + declaredPath);
        }
        return resolved;
    }

    private String extractSnippet(Path file,
                                  Set<String> targetMethods,
                                  int remainingChars) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        int maxChars = Math.min(MAX_CHARS_PER_FILE, remainingChars);
        if (source.length() <= maxChars) {
            return source;
        }

        int targetIndex = findTargetIndex(source, targetMethods);
        int start = targetIndex < 0 ? 0 : Math.max(0, targetIndex - 2_000);
        int end = Math.min(source.length(), start + maxChars);
        String prefix = start == 0 ? "" : "[省略前文]\n";
        String suffix = end == source.length() ? "" : "\n[省略后文]";
        return prefix + source.substring(start, end) + suffix;
    }

    private int findTargetIndex(String source, Set<String> targetMethods) {
        for (String method : targetMethods) {
            int index = source.indexOf(method);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }
}
