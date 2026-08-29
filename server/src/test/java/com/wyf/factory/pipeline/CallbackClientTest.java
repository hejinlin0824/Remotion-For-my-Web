package com.wyf.factory.pipeline;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * CallbackClient 契约（全 fake，HTTP 走 JDK 内置 HttpServer 回环，零外呼）：
 * 成功单发 / 非 2xx 退避重试后成功 / 超时按退避重试到底 / 最终失败仅 warn 不抛。
 * 日志红线（只含 URL 不含载荷）靠约定与评审，测试断言行为不探针日志。
 */
class CallbackClientTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    /** 每次请求依次弹出的响应码；耗尽后一律 200。 */
    private final List<Integer> responseQueue = new CopyOnWriteArrayList<>();
    /** 记录到的请求体（UTF-8）。 */
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    /** 注入的退避睡眠记录（毫秒）。 */
    private final List<Long> backoffs = new ArrayList<>();
    private String callbackUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = silentServer(false);
        server.start();
        callbackUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/cb";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 回环假端点：slow=true 时挂住不响应（制造请求超时），否则弹 responseQueue（耗尽后 200）。 */
    private HttpServer silentServer(boolean slow) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // 默认 executor 是单线程顺序处理，慢请求会把后续重试请求压在队列里 → 必须用线程池并发处理
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        http.createContext("/cb", exchange -> {
            hits.incrementAndGet();
            if (slow) {
                try {
                    Thread.sleep(2000);   // 远超测试用 200ms 请求超时
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                try (InputStream in = exchange.getRequestBody()) {
                    bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            int code = slow || responseQueue.isEmpty() ? 200 : responseQueue.remove(0);
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        return http;
    }

    private CallbackClient client() {
        return new CallbackClient(Duration.ofSeconds(5), backoffs::add);
    }

    @Test
    @DisplayName("成功：POST JSON 载荷一次到位，无退避；videoUrl/error 为 null 不序列化")
    void success_postsPayloadOnce() {
        client().notify(callbackUrl,
                new CallbackClient.CallbackPayload("job-1", "DONE", "http://v/1", null));

        assertThat(hits).hasValue(1);
        assertThat(backoffs).isEmpty();
        assertThat(bodies.get(0))
                .contains("\"jobId\":\"job-1\"")
                .contains("\"status\":\"DONE\"")
                .contains("\"videoUrl\":\"http://v/1\"")
                .doesNotContain("error");
    }

    @Test
    @DisplayName("非 2xx：按 1s/2s 退避重试，第 3 次成功")
    void retriesOnServerError_thenSucceeds() {
        responseQueue.add(500);
        responseQueue.add(503);

        client().notify(callbackUrl,
                new CallbackClient.CallbackPayload("job-2", "FAILED", null, "boom"));

        assertThat(hits).hasValue(3);
        assertThat(backoffs).containsExactly(1000L, 2000L);   // 指数退避基数 1s
        assertThat(bodies.get(2)).contains("\"error\":\"boom\"");
    }

    @Test
    @DisplayName("超时：每次尝试都超时 → 首次 + 3 次重试后放弃，仅日志不抛")
    void timeout_exhaustsRetriesQuietly() throws IOException {
        server.stop(0);
        HttpServer slow = silentServer(true);
        server = slow;
        slow.start();
        callbackUrl = "http://127.0.0.1:" + slow.getAddress().getPort() + "/cb";

        CallbackClient timeoutClient = new CallbackClient(Duration.ofMillis(200), backoffs::add);
        assertThatCode(() -> timeoutClient.notify(callbackUrl,
                new CallbackClient.CallbackPayload("job-3", "CANCELLED", null, null)))
                .doesNotThrowAnyException();
        assertThat(hits).hasValue(4);
        assertThat(backoffs).containsExactly(1000L, 2000L, 4000L);
    }

    @Test
    @DisplayName("最终失败：重试耗尽不抛异常（失败仅日志，绝不影响任务终态）")
    void finalFailure_doesNotThrow() {
        for (int i = 0; i < 10; i++) {
            responseQueue.add(500);
        }
        assertThatCode(() -> client().notify(callbackUrl,
                new CallbackClient.CallbackPayload("job-4", "DONE", null, null)))
                .doesNotThrowAnyException();
        assertThat(hits).hasValue(4);   // 首次 + 3 次重试后放弃
    }
}
