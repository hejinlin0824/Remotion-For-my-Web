package com.wyf.factory.glm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.config.Secrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlmClientTest {

    private static final String KEY = "test-key-do-not-leak";
    private static final String URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    /** 脚本化 fake transport：按序吐出响应/异常，并记录每次收到的请求与请求体。 */
    private static final class FakeTransport implements HttpTransport {
        final List<HttpRequest> requests = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        private final Deque<Object> script = new ArrayDeque<>();

        FakeTransport enqueue(HttpResponse<byte[]> response) {
            script.add(response);
            return this;
        }

        FakeTransport enqueue(IOException error) {
            script.add(error);
            return this;
        }

        @Override
        public HttpResponse<byte[]> send(HttpRequest request, byte[] body) throws IOException {
            requests.add(request);
            bodies.add(body);
            Object next = script.poll();
            if (next instanceof IOException io) {
                throw io;
            }
            @SuppressWarnings("unchecked")
            HttpResponse<byte[]> response = (HttpResponse<byte[]>) next;
            return response;
        }
    }

    private static HttpResponse<byte[]> response(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        URI uri = URI.create(URL);
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return HttpRequest.newBuilder(uri).build(); }
            @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            @Override public byte[] body() { return bytes; }
            @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return uri; }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        };
    }

    private static String okBody(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
    }

    private Secrets secrets() {
        return new Secrets(Map.of("ZHIPU_API_KEY", KEY)::get, tempDir.resolve("absent-secrets.local.yml"));
    }

    /** 退避基数 1ms：单测不等真退避。 */
    private GlmClient client(HttpTransport transport) {
        return new GlmClient(transport, secrets(), new AppProperties(), 1);
    }

    @Test
    @DisplayName("chat：POST {base-url}/chat/completions，Authorization=Bearer、Content-Type=application/json")
    void chat_requestShape_urlAndHeaders() {
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(response(200, okBody("好的")));
        String reply = client(transport).chat("你是审题员", "1+1=?");

        assertThat(reply).isEqualTo("好的");
        assertThat(transport.requests).hasSize(1);
        HttpRequest request = transport.requests.get(0);
        assertThat(request.uri().toString()).isEqualTo(URL);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + KEY);
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
    }

    @Test
    @DisplayName("chat：请求体形状 model/messages(system,user)/temperature=0.2/max_tokens=8192")
    void chat_requestBody_shape() throws Exception {
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(response(200, okBody("ok")));
        client(transport).chat("系统提示", "用户题目");

        JsonNode body = JSON.readTree(transport.bodies.get(0));
        assertThat(body.path("model").asText()).isEqualTo("glm-5.3-flash");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(8192);
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("系统提示");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("用户题目");
    }

    @Test
    @DisplayName("chatWithImage：user.content 为 image_url 形态 data:<mime>;base64,<data>")
    void chatWithImage_imageUrlShape() throws Exception {
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(response(200, okBody("PASS")));
        String reply = client(transport).chatWithImage("审这帧", "QUJD", "image/png");

        assertThat(reply).isEqualTo("PASS");
        JsonNode content = JSON.readTree(transport.bodies.get(0)).path("messages").get(1).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content).hasSize(1);
        JsonNode part = content.get(0);
        assertThat(part.path("type").asText()).isEqualTo("image_url");
        assertThat(part.path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,QUJD");
        // system 消息保持纯文本形态
        JsonNode system = JSON.readTree(transport.bodies.get(0)).path("messages").get(0);
        assertThat(system.path("content").asText()).isEqualTo("审这帧");
    }

    @Test
    @DisplayName("429 → 退避后重试 → 第二次 200 成功，共调用 2 次")
    void retryOn429_thenSucceed() {
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(response(429, "{\"error\":\"rate limited\"}"))
                .enqueue(response(200, okBody("第二次成功")));
        String reply = client(transport).chat("s", "u");

        assertThat(reply).isEqualTo("第二次成功");
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    @DisplayName("5xx → 同样退避重试（500,503,200 三次内成功）")
    void retryOn5xx_thenSucceed() {
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(response(500, "boom"))
                .enqueue(response(503, "unavailable"))
                .enqueue(response(200, okBody("恢复")));
        assertThat(client(transport).chat("s", "u")).isEqualTo("恢复");
        assertThat(transport.requests).hasSize(3);
    }

    @Test
    @DisplayName("连续 3 次 429 → 重试耗尽抛 retryable=true 异常，异常消息不含 key")
    void threeTimes429_exhaustsRetries_retryable() {
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(response(429, "r1"))
                .enqueue(response(429, "r2"))
                .enqueue(response(429, "r3"));

        assertThatThrownBy(() -> client(transport).chat("s", "u"))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("429")
                .hasMessageNotContaining(KEY)
                .extracting("retryable")
                .isEqualTo(true);
        assertThat(transport.requests).hasSize(3);
        // 每次尝试都带着认证头
        assertThat(transport.requests).allSatisfy(r ->
                assertThat(r.headers().firstValue("Authorization")).contains("Bearer " + KEY));
    }

    @Test
    @DisplayName("400（4xx 非 429）→ 不重试直接抛 retryable=false，响应体片段进异常消息")
    void clientError4xx_noRetry() {
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(response(400, "{\"error\":\"bad request\"}"));

        assertThatThrownBy(() -> client(transport).chat("s", "u"))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("bad request")
                .extracting("retryable")
                .isEqualTo(false);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    @DisplayName("非 2xx 响应体超 500 字符 → 截断 500 字符进异常消息")
    void errorBody_truncatedTo500Chars() {
        String huge = "A".repeat(500) + "TAIL-MARKER";
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(response(400, huge));

        assertThatThrownBy(() -> client(transport).chat("s", "u"))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("GLM HTTP 400")
                .hasMessageNotContaining("TAIL-MARKER");
    }

    @Test
    @DisplayName("IOException（IO/超时）→ 视为瞬态，3 次耗尽抛 retryable=true")
    void ioException_retryableAfterExhaustion() {
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(new IOException("timed out"))
                .enqueue(new IOException("timed out"))
                .enqueue(new IOException("timed out"));

        assertThatThrownBy(() -> client(transport).chat("s", "u"))
                .isInstanceOf(GlmException.class)
                .hasMessageNotContaining(KEY)
                .extracting("retryable")
                .isEqualTo(true);
        assertThat(transport.requests).hasSize(3);
    }

    @Test
    @DisplayName("200 但正文为空（thinking 吃满预算）→ 视为瞬态，重试后成功")
    void emptyContent_isTransient() {
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(response(200, okBody("")))
                .enqueue(response(200, okBody("补上的正文")));

        assertThat(client(transport).chat("s", "u")).isEqualTo("补上的正文");
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    @DisplayName("200 但 JSON 缺 choices → 视为瞬态，3 次耗尽抛 retryable=true")
    void malformed200_retryable() {
        FakeTransport transport = (FakeTransport) new FakeTransport()
                .enqueue(response(200, "{\"oops\":1}"))
                .enqueue(response(200, "{\"oops\":1}"))
                .enqueue(response(200, "{\"oops\":1}"));

        assertThatThrownBy(() -> client(transport).chat("s", "u"))
                .isInstanceOf(GlmException.class)
                .extracting("retryable")
                .isEqualTo(true);
        assertThat(transport.requests).hasSize(3);
    }

    @Test
    @DisplayName("key 缺失 → 直接抛不可重试 GlmException，不发任何请求")
    void missingKey_failsFast() {
        Secrets noKey = new Secrets(name -> null, tempDir.resolve("absent-secrets.local.yml"));
        FakeTransport transport = new FakeTransport();
        GlmClient c = new GlmClient(transport, noKey, new AppProperties(), 1);

        assertThatThrownBy(() -> c.chat("s", "u"))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("ZHIPU_API_KEY")
                .extracting("retryable")
                .isEqualTo(false);
        assertThat(transport.requests).isEmpty();
    }
}
