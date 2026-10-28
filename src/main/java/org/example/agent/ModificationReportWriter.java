package org.example.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 将一次实际写入的代码修改整理为易读的 Markdown 说明文档。 */
final class ModificationReportWriter {

    private ModificationReportWriter() {
    }

    /**
     * 写出本轮的修改范围、原因和原始位置，不重复保存完整源码。
     */
    static void write(Path reportPath,
                      int iteration,
                      JsonObject decision,
                      JsonObject changeSet,
                      CodeChangeApplier.ApplicationResult applicationResult) throws IOException {
        JsonObject selectedPlan = decision.getAsJsonObject("selectedPlan");
        String planId = stringValue(selectedPlan, "id");
        String planSummary = stringValue(selectedPlan, "summary");

        StringBuilder markdown = new StringBuilder();
        markdown.append("# 代码修改说明\n\n");
        markdown.append("- 迭代轮次：").append(iteration).append('\n');
        markdown.append("- 方案编号：").append(planId).append('\n');
        markdown.append("- 代码地图目标：").append(stringValue(selectedPlan, "targetId")).append('\n');
        markdown.append("- 方案摘要：").append(planSummary).append('\n');
        markdown.append("- 修改 Agent 摘要：").append(stringValue(changeSet, "summary")).append("\n\n");
        markdown.append("## 实际修改的代码\n\n");

        for (CodeChangeApplier.AppliedChange change : applicationResult.appliedChanges()) {
            markdown.append("### `").append(change.file()).append(":")
                    .append(change.startLine()).append("- ").append(change.endLine()).append("`\n\n");
            markdown.append("- 修改内容：").append(change.description()).append('\n');
            markdown.append("- 修改原因：").append(change.reason()).append("\n\n");
        }

        markdown.append("## 安全与验证\n\n");
        markdown.append("- 每项修改都只能使用代码地图中预登记的唯一修改点。\n");
        markdown.append("- 修改前的源文件已保存至本轮 `backups` 目录。\n");
        markdown.append("- 验证失败时，工作流会从备份恢复源文件。\n");
        markdown.append("- 详细机器可读修改内容见同目录 `code-changes.json`。\n");

        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, markdown.toString(), StandardCharsets.UTF_8);
    }

    /** 在验证完成后补充本轮修改最终是否保留，避免文档与源码状态不一致。 */
    static void appendValidationResult(Path reportPath, boolean passed) throws IOException {
        String message = passed
                ? "\n## 验证结果\n\n本轮修改已通过确定性验证，源码修改已保留。\n"
                : "\n## 验证结果\n\n本轮修改未通过验证，源码已从 `backups` 目录自动恢复。\n";
        Files.writeString(reportPath, message, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static String stringValue(JsonObject object, String field) {
        if (object == null) {
            return "";
        }
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? "" : value.getAsString();
    }
}
