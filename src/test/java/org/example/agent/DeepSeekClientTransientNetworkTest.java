package org.example.agent;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DeepSeekClient 瞬时网络错误的轻量级回归测试。
 *
 * <p>本测试不读取配置，也不请求真实 DeepSeek API；通过模拟两次 Connection reset
 * 后成功的请求，验证客户端会重新创建连接并有限重试。</p>
 */
public final class DeepSeekClientTransientNetworkTest {

    private DeepSeekClientTransientNetworkTest() {
    }

    public static void main(String[] args) throws Exception {
        retriesConnectionResetAndThenSucceeds();
        doesNotRetryBusinessIOException();
        System.out.println("DeepSeekClientTransientNetworkTest passed.");
    }

    private static void retriesConnectionResetAndThenSucceeds() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicInteger refreshCount = new AtomicInteger();
        List<Integer> pauses = new ArrayList<>();

        String result = DeepSeekClient.executeWithTransientNetworkRetry(
                () -> {
                    if (requestCount.incrementAndGet() < 3) {
                        throw new SocketException("Connection reset");
                    }
                    return "ok";
                },
                refreshCount::incrementAndGet,
                pauses::add);

        require("ok".equals(result), "第三次请求应返回成功结果。");
        require(requestCount.get() == 3, "Connection reset 应重试两次。");
        require(refreshCount.get() == 2, "每次重试前应重新创建 HTTP 客户端。");
        require(pauses.equals(List.of(1, 2)), "重试等待次数应依次为 1、2。");
    }

    private static void doesNotRetryBusinessIOException() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();

        try {
            DeepSeekClient.executeWithTransientNetworkRetry(
                    () -> {
                        requestCount.incrementAndGet();
                        throw new IOException("HTTP 401");
                    },
                    () -> {
                    },
                    retryNumber -> {
                    });
            throw new AssertionError("HTTP 业务错误不应被吞掉。");
        } catch (IOException expected) {
            require(requestCount.get() == 1, "HTTP 业务错误不应重试。");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
