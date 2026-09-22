package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将代码修改 Agent 输出的精确文本替换安全地应用到工作区。
 *
 * <p>所有替换都会先在内存中校验：目标文件必须已被决策授权，原文必须唯一匹配。
 * 写入前会在当前迭代目录保存原文件，因此验证失败时可以恢复。</p>
 */
final class CodeChangeApplier {

    private final Path projectRoot;
    private final Path mainSourceRoot;
    private final Path testSourceRoot;

    CodeChangeApplier(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.mainSourceRoot = this.projectRoot.resolve("src/main/java").normalize();
        this.testSourceRoot = this.projectRoot.resolve("src/test/java").normalize();
    }

    /** 应用一个 changeSet，并把每个原文件备份到 iterationDirectory/backups。 */
    ApplicationResult apply(JsonObject decision,
                            JsonObject changeSet,
                            Path iterationDirectory) throws IOException {
        Set<String> allowedFiles = allowedFiles(decision);
        JsonArray changes = changeSet.getAsJsonArray("changes");
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("没有可应用的代码修改。");
        }

        Map<Path, String> originalSources = new LinkedHashMap<>();
        Map<Path, String> updatedSources = new LinkedHashMap<>();
        List<AppliedChange> appliedChanges = new ArrayList<>();

        // 先在内存中完成所有替换；任何一项不匹配时都不会写入源文件。
        for (JsonElement element : changes) {
            JsonObject change = element.getAsJsonObject();
            String declaredFile = change.get("file").getAsString();
            Path sourceFile = resolveAllowedFile(declaredFile, allowedFiles);
            String originalText = change.get("originalText").getAsString();
            String replacementText = change.get("replacementText").getAsString();

            String current = updatedSources.get(sourceFile);
            if (current == null) {
                current = Files.readString(sourceFile, StandardCharsets.UTF_8);
                originalSources.put(sourceFile, current);
            }

            int matchIndex = uniqueMatchIndex(current, originalText, declaredFile);
            int startLine = lineNumberAt(current, matchIndex);
            int endLine = lineNumberAt(current, matchIndex + originalText.length());
            String updated = current.substring(0, matchIndex)
                    + replacementText
                    + current.substring(matchIndex + originalText.length());
            updatedSources.put(sourceFile, updated);
            appliedChanges.add(new AppliedChange(
                    relativePath(sourceFile),
                    startLine,
                    endLine,
                    change.get("description").getAsString(),
                    change.get("reason").getAsString()));
        }

        Path backupDirectory = iterationDirectory.resolve("backups");
        Map<Path, Path> backups = new LinkedHashMap<>();
        try {
            for (Map.Entry<Path, String> source : originalSources.entrySet()) {
                Path backup = backupDirectory.resolve(relativePath(source.getKey()));
                Files.createDirectories(backup.getParent());
                Files.writeString(backup, source.getValue(), StandardCharsets.UTF_8);
                backups.put(source.getKey(), backup);
            }
            for (Map.Entry<Path, String> source : updatedSources.entrySet()) {
                Files.writeString(source.getKey(), source.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            restoreOriginalSources(originalSources);
            throw exception;
        }

        return new ApplicationResult(appliedChanges, backups);
    }

    /** 验证失败后从本轮备份恢复全部改动文件。 */
    void restore(ApplicationResult result) throws IOException {
        for (Map.Entry<Path, Path> entry : result.backups().entrySet()) {
            String original = Files.readString(entry.getValue(), StandardCharsets.UTF_8);
            Files.writeString(entry.getKey(), original, StandardCharsets.UTF_8);
        }
    }

    private Set<String> allowedFiles(JsonObject decision) {
        JsonObject selectedPlan = decision.getAsJsonObject("selectedPlan");
        JsonArray files = selectedPlan == null ? null : selectedPlan.getAsJsonArray("targetFiles");
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("selectedPlan 未声明允许修改的 targetFiles。");
        }

        Set<String> result = new LinkedHashSet<>();
        for (JsonElement file : files) {
            if (file.isJsonPrimitive()) {
                result.add(normalizeDeclaredPath(file.getAsString()));
            }
        }
        return result;
    }

    private Path resolveAllowedFile(String declaredFile, Set<String> allowedFiles) {
        String normalized = normalizeDeclaredPath(declaredFile);
        if (!allowedFiles.contains(normalized)) {
            throw new IllegalArgumentException("代码修改目标不在 selectedPlan.targetFiles 中: " + declaredFile);
        }
        if (normalized.startsWith("src/main/java/org/example/agent/")) {
            throw new IllegalArgumentException("代码修改阶段不允许改动 LLM 框架自身: " + declaredFile);
        }

        Path resolved = projectRoot.resolve(Path.of(normalized)).normalize();
        boolean inSourceDirectory = resolved.startsWith(mainSourceRoot) || resolved.startsWith(testSourceRoot);
        if (!normalized.endsWith(".java") || !inSourceDirectory || !Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("代码修改目标必须是已有的项目 Java 源文件: " + declaredFile);
        }
        return resolved;
    }

    private int uniqueMatchIndex(String source, String originalText, String declaredFile) {
        if (originalText == null || originalText.isEmpty()) {
            throw new IllegalArgumentException("原文不能为空: " + declaredFile);
        }
        int first = source.indexOf(originalText);
        if (first < 0) {
            throw new IllegalArgumentException("原文未在目标文件中匹配: " + declaredFile);
        }
        if (source.indexOf(originalText, first + 1) >= 0) {
            throw new IllegalArgumentException("原文在目标文件中出现多次，拒绝模糊替换: " + declaredFile);
        }
        return first;
    }

    private int lineNumberAt(String text, int index) {
        int line = 1;
        for (int position = 0; position < Math.min(index, text.length()); position++) {
            if (text.charAt(position) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String normalizeDeclaredPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("代码修改目标路径不能为空。");
        }
        Path normalized = Path.of(path).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("代码修改目标必须是项目内相对路径: " + path);
        }
        return normalized.toString().replace('\\', '/');
    }

    private String relativePath(Path path) {
        return projectRoot.relativize(path).toString().replace('\\', '/');
    }

    private void restoreOriginalSources(Map<Path, String> originalSources) {
        for (Map.Entry<Path, String> source : originalSources.entrySet()) {
            try {
                Files.writeString(source.getKey(), source.getValue(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // 原始写入异常会被调用方接收；此处仅尽力避免半写入状态。
            }
        }
    }

    /** 一项已写入源码的修改，用于生成面向用户的 Markdown 文档。 */
    record AppliedChange(String file,
                         int startLine,
                         int endLine,
                         String description,
                         String reason) {
    }

    /** 本轮写入的修改和对应备份文件。 */
    record ApplicationResult(List<AppliedChange> appliedChanges, Map<Path, Path> backups) {
    }
}
