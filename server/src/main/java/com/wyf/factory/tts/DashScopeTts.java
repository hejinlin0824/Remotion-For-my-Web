package com.wyf.factory.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.config.Secrets;
import com.wyf.factory.glm.GlmException;
import com.wyf.factory.glm.HttpTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DashScope qwen-tts HTTP 客户端（gen_tts_template.py:87-98 synth 的 HTTP 直调移植）。
 *
 * <p>形状照脚本：北京区域 multimodal-generation 端点、Bearer DASHSCOPE key、
 * model=qwen-tts、voice=Cherry、不传 rate/format（脚本亦不传 → 默认 wav/常速，
 * audio_meta 的 rate=1.0 只是记录值）。响应递归找 output.audio.url（find_audio_url 移植）
 * 再 GET 下载 wav（requests.get(url, timeout=180) 对应物）。非流式产物即 wav。</p>
 *
 * <p>重试：429/5xx/IO/无 URL → 指数退避重试 3 次（2s/4s/8s），耗尽抛 retryable=true；
 * 4xx（除 429）→ 不重试直接抛 retryable=false。每次 429 记入 {@link #rateLimitEvents()}
 * ——15s 冷却的时间戳归 TtsPipeline 层管（本客户端只管退避）。HTTP 层成功 ≠ 音频完整，
 * 完整性归 RmsCheck。</p>
 *
 * <p>key 零泄漏：不打请求头/密钥日志；异常消息只含状态码与响应体片段。</p>
 */
@Component
public class DashScopeTts {

    /** DashScope（北京区域）qwen-tts 非流式端点。 */
    static final String DEFAULT_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    /** 与 gen_tts_template.py:19 逐字一致。 */
    static final String MODEL = "qwen-tts";
    static final String VOICE = "Cherry";
    /** 429/5xx 重试次数（总尝试 = 1 + 3；间隔 2s/4s/8s）。 */
    private static final int MAX_RETRIES = 3;
    private static final int ERROR_BODY_SNIPPET = 500;
    private static final long DEFAULT_BACKOFF_BASE_MILLIS = 2000L;
    private static final Duration AUDIO_DOWNLOAD_TIMEOUT = Duration.ofSeconds(180);

    private final HttpTransport transport;
    private final Secrets secrets;
    private final long backoffBaseMillis;
    private final TtsPipeline.Sleeper sleeper;
    private final AudioFetcher audioFetcher;
    private final AtomicLong rateLimitEvents = new AtomicLong();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Spring 生产构造（唯一 @Autowired 构造器）：JDK HttpClient 下载音频、真退避。 */
    @Autowired
    public DashScopeTts(HttpTransport transport, Secrets secrets, AppProperties props) {
        this(transport, secrets, props, DEFAULT_BACKOFF_BASE_MILLIS, TtsPipeline.Sleeper.SYSTEM,
                new JdkAudioFetcher());
    }

    /** 测试构造（包内可见防 Spring 误选）：注入退避基数/睡眠/音频下载（单测零等待、零网络）。 */
    DashScopeTts(HttpTransport transport, Secrets secrets, AppProperties props,
                 long backoffBaseMillis, TtsPipeline.Sleeper sleeper, AudioFetcher audioFetcher) {
        this.transport = transport;
        this.secrets = secrets;
        this.backoffBaseMillis = backoffBaseMillis;
        this.sleeper = sleeper;
        this.audioFetcher = audioFetcher;
    }

    /** 合成一句，返回 wav 字节。HTTP 层耗尽/致命错误抛 {@link GlmException}。 */
    public byte[] synthesize(String text) {
        byte[] body = buildBody(text);
        GlmException lastTransient = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                sleepBackoff(attempt);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(DEFAULT_URL))
                    .header("Authorization", "Bearer " + secrets.ttsKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                HttpResponse<byte[]> response = transport.send(request, body);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    String url = findAudioUrl(mapper.readTree(response.body()));
                    if (url == null) {
                        lastTransient = new GlmException(
                                "DashScope 响应 200 但无音频 URL（视为瞬态）：" + snippet(response.body()), true);
                        continue;
                    }
                    return audioFetcher.fetch(url);
                }
                if (status == 429) {
                    rateLimitEvents.incrementAndGet();
                }
                GlmException failure = new GlmException(
                        "DashScope HTTP " + status + "：" + snippet(response.body()),
                        status == 429 || status >= 500);
                if (!failure.isRetryable()) {
                    throw failure;
                }
                lastTransient = failure;
            } catch (IOException e) {
                lastTransient = new GlmException(
                        "DashScope 请求 IO/超时失败（视为瞬态）：" + e.getMessage(), true, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GlmException("DashScope 请求被中断", true, e);
            }
        }
        throw lastTransient != null
                ? lastTransient
                : new GlmException("DashScope 调用失败：重试耗尽", true);
    }

    /** 累计观测到的 429 次数（TtsPipeline 用前后差值判定「本 attempt 出现过 429」）。 */
    public long rateLimitEvents() {
        return rateLimitEvents.get();
    }

    private byte[] buildBody(String text) {
        ObjectNode input = mapper.createObjectNode();
        input.put("text", text);
        input.put("voice", VOICE);
        ObjectNode root = mapper.createObjectNode();
        root.put("model", MODEL);
        root.set("input", input);
        try {
            return mapper.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new GlmException("DashScope 请求体序列化失败", false, e);
        }
    }

    /** find_audio_url 移植：递归找键名 url/audio_url 且值为 http 开头字符串的第一个命中。 */
    private String findAudioUrl(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase();
                JsonNode value = field.getValue();
                if (("url".equals(key) || "audio_url".equals(key))
                        && value.isTextual() && value.asText().startsWith("http")) {
                    return value.asText();
                }
                String found = findAudioUrl(value);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String found = findAudioUrl(item);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 第 attempt 次（attempt≥1）重试前的退避：2s / 4s / 8s。 */
    private void sleepBackoff(int attempt) {
        try {
            sleeper.sleep(backoffBaseMillis << (attempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GlmException("DashScope 重试退避被中断", true, e);
        }
    }

    private static String snippet(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8);
        return s.length() > ERROR_BODY_SNIPPET ? s.substring(0, ERROR_BODY_SNIPPET) : s;
    }

    /** 音频 URL → wav 字节（可注入 fake）。 */
    @FunctionalInterface
    public interface AudioFetcher {
        byte[] fetch(String url) throws IOException, InterruptedException;
    }

    /** 生产下载：JDK HttpClient GET（requests.get(url, timeout=180) 对应物）。 */
    private static final class JdkAudioFetcher implements AudioFetcher {

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        @Override
        public byte[] fetch(String url) throws IOException, InterruptedException {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(AUDIO_DOWNLOAD_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("音频下载 HTTP " + response.statusCode());
            }
            return response.body();
        }
    }
}
