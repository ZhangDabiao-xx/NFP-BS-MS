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
import java.util.List;
import java.util.Map;

/**
 * 根据代码地图的 editPointId 确定性写入代码修改。
 *
 * <p>模型不会提供文件路径、方法名或原文片段。本类只接受代码地图中已验证的唯一
 * 锚点，因此既避免换行符造成的误匹配，也不会把修改写到未授权位置。</p>
 */
final class CodeChangeApplier {

    private final Path projectRoot;

    CodeChangeApplier(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /** 应用一个 changeSet，并把每个原文件备份到 iterationDirectory/backups。 */
    ApplicationResult apply(ProjectCodeMap codeMap,
                            JsonObject decision,
                            JsonObject changeSet,
                            Path iterationDirectory) throws IOException {
        codeMap.validateChangeSet(decision, changeSet);
        JsonArray changes = changeSet.getAsJsonArray("changes");
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("没有可应用的代码修改。");
        }

        Map<Path, String> originalSources = new LinkedHashMap<>();
        Map<Path, String> updatedSources = new LinkedHashMap<>();
        List<AppliedChange> appliedChanges = new ArrayList<>();

        // 先在内存中完成全部修改；任何锚点异常都不会写入生产源码。
        for (JsonElement element : changes) {
            JsonObject change = element.getAsJsonObject();
            String pointId = stringValue(change, "editPointId");
            ProjectCodeMap.EditPoint point = codeMap.editPoint(decision, pointId);
            Path sourceFile = codeMap.sourceFile(point);
            String replacementText = stringValue(change, "replacementText");
            if (replacementText.isBlank()) {
                throw new IllegalArgumentException("replacementText 不能为空: " + pointId);
            }

            String current = updatedSources.get(sourceFile);
            if (current == null) {
                current = Files.readString(sourceFile, StandardCharsets.UTF_8);
                originalSources.put(sourceFile, current);
            }

            TextRange range = editableRange(current, point);
            String updated = current.substring(0, range.start())
                    + withTrailingLineBreak(replacementText)
                    + current.substring(range.end());
            updatedSources.put(sourceFile, updated);
            appliedChanges.add(new AppliedChange(
                    relativePath(sourceFile),
                    range.startLine(),
                    range.endLine(),
                    stringValue(change, "description"),
                    stringValue(change, "reason")));
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

    /**
     * 用预登记锚点确定本次可替换范围。
     *
     * <p>范围不包含开始或结束锚点；这样模型只需返回实际修改区域，
     * 程序会保留后续监控语句。锚点是短而唯一的源码事实，不受模型换行格式影响。</p>
     */
    private TextRange editableRange(String source, ProjectCodeMap.EditPoint point) {
        int start = uniqueMatchIndex(source, point.startAnchor(), point.id(), "开始");
        int end = uniqueMatchIndex(source, point.endAnchor(), point.id(), "结束");
        if (end <= start) {
            throw new IllegalArgumentException("修改点锚点顺序错误: " + point.id());
        }
        int contentStart = afterLineBreak(source, start + point.startAnchor().length());
        return new TextRange(contentStart, end,
                lineNumberAt(source, contentStart), lineNumberAt(source, end));
    }

    private int uniqueMatchIndex(String source, String anchor, String pointId, String position) {
        int first = source.indexOf(anchor);
        if (first < 0) {
            throw new IllegalArgumentException(position + "锚点未在目标文件中匹配: " + pointId);
        }
        if (source.indexOf(anchor, first + 1) >= 0) {
            throw new IllegalArgumentException(position + "锚点在目标文件中出现多次: " + pointId);
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

    /** 模型若省略末尾换行，也不能让结束锚点与最后一行代码粘连。 */
    private String withTrailingLineBreak(String replacementText) {
        return replacementText.endsWith("\n") || replacementText.endsWith("\r")
                ? replacementText : replacementText + System.lineSeparator();
    }

    private int afterLineBreak(String source, int index) {
        if (index < source.length() && source.charAt(index) == '\r') {
            index++;
        }
        if (index < source.length() && source.charAt(index) == '\n') {
            index++;
        }
        return index;
    }

    private String relativePath(Path path) {
        return projectRoot.relativize(path).toString().replace('\\', '/');
    }

    private void restoreOriginalSources(Map<Path, String> originalSources) {
        for (Map.Entry<Path, String> source : originalSources.entrySet()) {
            try {
                Files.writeString(source.getKey(), source.getValue(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // 原始写入异常会被调用方接收；这里仅尽力避免半写入状态。
            }
        }
    }

    private String stringValue(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? "" : value.getAsString();
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

    private record TextRange(int start, int end, int startLine, int endLine) {
    }
}
