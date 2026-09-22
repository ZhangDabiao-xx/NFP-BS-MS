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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从候选方案中提取目标 Java 文件的有限代码片段，供可行性审查使用。
 *
 * <p>只允许读取 {@code src/main/java} 和 {@code src/test/java} 内、且方案明确列出的
 * Java 文件，防止方案文本诱导 Agent 读取无关文件或敏感配置。</p>
 */
final class SourceContextReader {

    private static final int MAX_FILE_COUNT = 6;
    private static final int MAX_TOTAL_CHARS = 40_000;
    private static final int MAX_CHARS_PER_FILE = 30_000;
    /** 目标方法前保留少量字段和常量，帮助代码 Agent 理解方法依赖。 */
    private static final int METHOD_CONTEXT_CHARS = 2_000;

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

    /**
     * 在决策前读取代码地图中的真实方法上下文。
     *
     * <p>这一步只读取框架预先确认的优化入口，因此 Decision Agent 能依据实际实现
     * 选择方案，不需要请求用户手工粘贴源码。</p>
     */
    String readForCodeTargets(JsonArray codeTargets) throws IOException {
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        if (codeTargets != null) {
            for (JsonElement element : codeTargets) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject target = element.getAsJsonObject();
                JsonElement file = target.get("file");
                JsonElement method = target.get("method");
                if (file != null && file.isJsonPrimitive()
                        && method != null && method.isJsonPrimitive()) {
                    targets.computeIfAbsent(file.getAsString(), ignored -> new LinkedHashSet<>())
                            .add(method.getAsString());
                }
            }
        }
        return readTargets(targets, "代码地图未声明可读取的真实优化入口。");
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
            boolean sourceExists = Files.isRegularFile(file);
            String snippet = sourceExists
                    ? extractSnippet(file, target.getValue(), remainingChars)
                    : "[该目标是计划新建的 Java 文件，当前没有可供审查的源码。]";
            if (sourceExists) {
                ensureTargetMethodsIncluded(snippet, target.getValue(), target.getKey());
            }
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

        int declarationIndex = findTargetDeclaration(source, targetMethods);
        if (declarationIndex < 0) {
            throw new IllegalArgumentException("无法在目标文件中定位真实方法声明: " + file);
        }

        int openingBrace = source.indexOf('{', declarationIndex);
        int methodEnd = findMatchingBrace(source, openingBrace);
        if (openingBrace < 0 || methodEnd < 0) {
            throw new IllegalArgumentException("无法定位目标方法的完整边界: " + file);
        }

        int start = Math.max(0, declarationIndex - METHOD_CONTEXT_CHARS);
        int end = methodEnd + 1;
        String snippet = source.substring(start, end);
        if (snippet.length() > maxChars) {
            throw new IllegalArgumentException("目标方法超过源码上下文长度限制: " + file);
        }

        String prefix = start == 0 ? "" : "[省略前文]\n";
        String suffix = end == source.length() ? "" : "\n[省略后文]";
        return prefix + snippet + suffix;
    }

    /**
     * 匹配 Java 方法声明，而不是查找方法名的首次文字出现位置。
     *
     * <p>例如 ImproveByRepack 在类顶部注释中也会出现；若只用 indexOf，
     * 会错误截取文件开头，导致代码修改 Agent 看不到真正方法体。</p>
     */
    private int findTargetDeclaration(String source, Set<String> targetMethods) {
        for (String method : targetMethods) {
            String methodName = methodName(method);
            Pattern declaration = Pattern.compile(
                    "(?m)^\\s*(?:public|protected|private)\\s+(?:static\\s+)?[^\\n{;]*\\b"
                            + Pattern.quote(methodName) + "\\s*\\(");
            Matcher matcher = declaration.matcher(source);
            if (matcher.find()) {
                return matcher.start();
            }
        }
        return -1;
    }

    /** 使用简单的括号计数确定一个已定位 Java 方法的结尾。 */
    private int findMatchingBrace(String source, int openingBrace) {
        if (openingBrace < 0) {
            return -1;
        }
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** 在调用代码修改 Agent 前确认片段中确实有请求的目标方法声明。 */
    private void ensureTargetMethodsIncluded(String snippet,
                                             Set<String> targetMethods,
                                             String declaredFile) {
        for (String method : targetMethods) {
            String methodName = methodName(method);
            Pattern declaration = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(");
            if (!declaration.matcher(snippet).find()) {
                throw new IllegalArgumentException("源码片段未包含目标方法 "
                        + method + ": " + declaredFile);
            }
        }
    }

    private String methodName(String method) {
        int separator = method.lastIndexOf('.');
        return separator >= 0 ? method.substring(separator + 1) : method;
    }
}
