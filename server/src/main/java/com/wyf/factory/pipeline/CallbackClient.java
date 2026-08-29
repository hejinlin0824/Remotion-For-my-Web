package com.wyf.factory.pipeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.LongConsumer;

/**
 * 终态回调客户端（plan Task 10）：POST JSON {@code {jobId,status,videoUrl?,error?}} 到
 * 任务入队时的 callbackUrl。请求超时 10s，失败按 1s/2s/4s 指数退避重试 3 次
 * （共 4 次尝试），全部失败仅 warn 日志（含 URL，绝不含载荷内容——日志红线），
 * 不向上抛：回调失败绝不影响任务终态。
 */
@Component
public class CallbackClient {

    private static final Logger log = LoggerFactory.getLogger(CallbackClient.class);

    /** 失败后的额外重试次数（总尝试 = 1 + MAX_RETRIES）。 */
    static final int MAX_RETRIES = 3;
    /** 退避基数（毫秒）：1s / 2s / 4s。 */
    static final long BACKOFF_BASE_MILLIS = 1000L;
    /** 默认请求超时（brief 指定 10s）。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final Duration requestTimeout;
    private final LongConsumer backoffSleeper;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 生产构造：10s 超时 + 真实退避睡眠。 */
    public CallbackClient() {
        this(DEFAULT_TIMEOUT, CallbackClient::systemSleep);
    }

    /** 测试构造：注入超时与退避睡眠（单测传 no-op 免真等）。 */
    CallbackClient(Duration requestTimeout, LongConsumer backoffSleeper) {
        this.requestTimeout = requestTimeout;
        this.backoffSleeper = backoffSleeper;
        this.http = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    }

    /** 回调载荷：videoUrl 仅 DONE 有值、error 仅 FAILED 有值（null 字段不序列化）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CallbackPayload(String jobId, String status, String videoUrl, String error) {
    }

    /** 通知终态；任何失败都不抛出。2xx 视为成功。 */
    public void notify(String callbackUrl, CallbackPayload payload) {
        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (IOException e) {
            // 载荷是三个 String，序列化不会失败；纯防御
            throw new IllegalStateException("回调载荷序列化失败", e);
        }
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                backoffSleeper.accept(BACKOFF_BASE_MILLIS << (attempt - 1));
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(callbackUrl))
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;   // 被中断：放弃重试
            } catch (IOException ignored) {
                // IO/超时：按退避重试
            }
        }
        log.warn("回调最终失败（共 {} 次尝试，失败仅记录不影响任务终态）url={}", MAX_RETRIES + 1, callbackUrl);
    }

    private static void systemSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
