package org.example.agent;

import java.net.URI;
import java.time.Duration;

/**
 * DeepSeek 客户端配置。
 *
 * <p>为了让示例可以直接运行，配置项集中写在本类顶部。使用前只需要修改
 * {@link #API_KEY}；如果接口地址或模型名不同，也可以同时修改对应常量。</p>
 */
public final class DeepSeekConfig {

    /**
     * 在这里填写你的 DeepSeek API Key。
     *
     * <p>不要把真实 Key 提交到公开仓库或上传到网络。</p>
     */
    private static final String API_KEY = "sk-7c802a9a76f24191b4de5a805d6405e8";

    /** DeepSeek Chat Completions 接口地址。 */
    private static final String API_URL =
            "https://api.deepseek.com/chat/completions";

    /** 模型名称；如果账户使用其他模型，直接修改这里。 */
    private static final String MODEL = "deepseek-v4-pro";

    /** 单次请求超时时间，单位为秒。 */
    private static final long TIMEOUT_SECONDS = 120L;

    private final String apiKey;
    private final URI apiUri;
    private final String model;
    private final Duration requestTimeout;

    public DeepSeekConfig(String apiKey,
                          URI apiUri,
                          String model,
                          Duration requestTimeout) {
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException("DeepSeek API Key 不能为空。");
        }
        if (apiUri == null
                || !("http".equalsIgnoreCase(apiUri.getScheme())
                || "https".equalsIgnoreCase(apiUri.getScheme()))) {
            throw new IllegalArgumentException("DeepSeek API 地址必须使用 http 或 https。");
        }
        if (isBlank(model)) {
            throw new IllegalArgumentException("DeepSeek 模型名不能为空。");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("DeepSeek 请求超时时间必须大于 0。");
        }

        this.apiKey = apiKey;
        this.apiUri = apiUri;
        this.model = model;
        this.requestTimeout = requestTimeout;
    }

    /**
     * 使用本类顶部的配置创建客户端配置。
     */
    public static DeepSeekConfig fromCode() {
        if (isPlaceholderApiKey(API_KEY)) {
            throw new IllegalStateException(
                    "请先在 DeepSeekConfig.API_KEY 中输入你的 DeepSeek API Key。");
        }
        return new DeepSeekConfig(
                API_KEY,
                URI.create(API_URL),
                MODEL,
                Duration.ofSeconds(TIMEOUT_SECONDS));
    }

    public String apiKey() {
        return apiKey;
    }

    public URI apiUri() {
        return apiUri;
    }

    public String model() {
        return model;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isPlaceholderApiKey(String value) {
        return isBlank(value)
                || value.contains("请在这里输入")
                || value.equals("YOUR_DEEPSEEK_API_KEY");
    }
}
