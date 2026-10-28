package org.example.agent;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 供多个 Agent 入口复用的 JSON 文件读写工具。 */
final class AgentJsonFiles {

    private AgentJsonFiles() {
    }

    /** 读取并确认文件根节点为 JSON 对象。 */
    static JsonObject readObject(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(description + "不存在: " + path.toAbsolutePath());
        }
        try {
            JsonElement element = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(description + "必须是 JSON 对象: " + path.toAbsolutePath());
            }
            return element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            throw new IllegalArgumentException(description + "不是合法 JSON: " + path.toAbsolutePath(), exception);
        }
    }

    /** 使用 UTF-8 和缩进格式写入 JSON；必要时自动创建父目录。 */
    static void writeObject(Path path, JsonObject value) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String formatted = new GsonBuilder().setPrettyPrinting().create().toJson(value);
        Files.writeString(path, formatted, StandardCharsets.UTF_8);
    }
}
