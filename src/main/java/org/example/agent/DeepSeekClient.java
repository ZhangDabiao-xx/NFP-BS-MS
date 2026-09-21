package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 使用 Java 标准 HttpClient 调用 DeepSeek Chat Completions API。
 *
 * <p>项目已经依赖 Gson，因此这里不再引入新的 HTTP 或模型 SDK 依赖，
 * 只实现当前第一步需要的同步 JSON 调用。</p>
 */
public final class DeepSeekClient {

    private final DeepSeekConfig config;
    private final HttpClient httpClient;

    public DeepSeekClient(DeepSeekConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .build();
    }

    /**
     * 请求普通文本结果。
     */
    public String chat(String systemPrompt,
                       String userPrompt) throws IOException, InterruptedException {
        return send(systemPrompt, userPrompt, false);
    }

    /**
     * 请求 JSON 对象结果，并在客户端再次检查返回内容确实是 JSON 对象。
     */
    public JsonObject chatJson(String systemPrompt,
                               String userPrompt) throws IOException, InterruptedException {
        String content = send(systemPrompt, userPrompt, true);
        try {
            JsonElement element = JsonParser.parseString(content);
            if (!element.isJsonObject()) {
                throw new IOException("DeepSeek 返回的 JSON 不是对象。");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new IOException("DeepSeek 返回内容不是合法 JSON：" + content, exception);
        }
    }

    private String send(String systemPrompt,
                        String userPrompt,
                        boolean jsonOutput) throws IOException, InterruptedException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", config.model());
        requestBody.add("messages", createMessages(systemPrompt, userPrompt));
        requestBody.addProperty("stream", false);

        // 结果分析 Agent 需要稳定的机器可读结果，因此开启 JSON Output。
        if (jsonOutput) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            requestBody.add("response_format", responseFormat);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(config.apiUri())
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody.toString(),
                        StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "DeepSeek API 请求失败，HTTP "
                            + response.statusCode()
                            + "，响应："
                            + response.body());
        }

        return extractContent(response.body());
    }

    private JsonArray createMessages(String systemPrompt, String userPrompt) {
        JsonArray messages = new JsonArray();
        messages.add(createMessage("system", systemPrompt));
        messages.add(createMessage("user", userPrompt));
        return messages;
    }

    private JsonObject createMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content == null ? "" : content);
        return message;
    }

    /**
     * 从 Chat Completions 响应中提取 choices[0].message.content。
     */
    private String extractContent(String responseBody) throws IOException {
        try {
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray choices = response.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("DeepSeek 响应缺少 choices 字段：" + responseBody);
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                throw new IOException("DeepSeek 响应缺少 message.content 字段：" + responseBody);
            }
            return message.get("content").getAsString();
        } catch (JsonSyntaxException | IllegalStateException exception) {
            throw new IOException("DeepSeek 响应不是合法 JSON：" + responseBody, exception);
        }
    }
}
