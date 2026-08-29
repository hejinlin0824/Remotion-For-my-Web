package com.wyf.factory.glm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.config.Secrets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * GLM 内容工位 HTTP 客户端（OpenAI 兼容 chat/completions，model 取 app.glm.model）。
 *
 * <p>与 Phase 1 验证过的真调脚本 template/scripts/qa_glm.py 对齐的经验：
 * model=glm-5.3-flash；Authorization: Bearer 认证；429/5xx 退避重试；
 * 200 但正文为空（thinking 吃满 token 预算）≠ 正常结果，视为瞬态重试。</p>
 *
 * <p>重试：429/5xx/IO/超时/空响应 → 指数退避（基数 2s），共 3 次尝试；
 * 4xx（除 429）→ 不重试直接抛 retryable=false。</p>
 *
 * <p>key 零泄漏：不打任何请求头/密钥日志；异常消息只含状态码与响应体片段。</p>
 */
@Component
public class GlmClient {

    /** 总尝试次数（含第一次）：3 次全败抛 retryable=true */
    static final int MAX_ATTEMPTS = 3;
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 8192;
    /** 非 2xx 响应体进异常消息的截断长度 */
    private static final int ERROR_BODY_SNIPPET = 500;
    /** 生产退避基数（毫秒）：重试间隔 2s / 4s */
    private static final long DEFAULT_BACKOFF_BASE_MILLIS = 2000L;

    private final HttpTransport transport;
    private final Secrets secrets;
    private final AppProperties props;
    private final long backoffBaseMillis;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Spring 生产构造（唯一 @Autowired 构造器，全上下文装配走这里）。 */
    @Autowired
    public GlmClient(HttpTransport transport, Secrets secrets, AppProperties props) {
        this(transport, secrets, props, DEFAULT_BACKOFF_BASE_MILLIS);
    }

    /** 测试构造（包内可见防 Spring 误选）：注入退避基数（毫秒），单测传 1 不等真退避。 */
    GlmClient(HttpTransport transport, Secrets secrets, AppProperties props, long backoffBaseMillis) {
        this.transport = transport;
        this.secrets = secrets;
        this.props = props;
        this.backoffBaseMillis = backoffBaseMillis;
    }

    /** 纯文本对话，返回助手回复 content。 */
    public String chat(String systemPrompt, String userPayload) {
        return doChat(systemPrompt, mapper.getNodeFactory().textNode(userPayload));
    }

    /** 视觉对话：user.content 为 image_url 形态 data:<mime>;base64,<data>。 */
    public String chatWithImage(String systemPrompt, String imageDataBase64, String mime) {
        ArrayNode parts = mapper.createArrayNode();
        ObjectNode image = parts.addObject();
        image.put("type", "image_url");
        image.putObject("image_url").put("url", "data:" + mime + ";base64," + imageDataBase64);
        return doChat(systemPrompt, parts);
    }

    private String doChat(String systemPrompt, JsonNode userContent) {
        String url = props.getGlm().getBaseUrl() + "/chat/completions";
        byte[] body = buildBody(systemPrompt, userContent);
        GlmException lastTransient = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleepBeforeRetry(attempt);
            }
            // key 每次尝试现取：未配置时立刻抛不可重试异常，不进重试循环
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + secrets.glmKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                HttpResponse<byte[]> response = transport.send(request, body);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    String content;
                    try {
                        content = parseContent(response.body());
                    } catch (IOException parseError) {
                        lastTransient = new GlmException("GLM 响应 JSON 解析失败（视为瞬态）", true, parseError);
                        continue;
                    }
                    if (content == null || content.isBlank()) {
                        // qa_glm.py：200 空正文（thinking 吃满预算）≠ 视觉缺陷/正常结果，按瞬态重试
                        lastTransient = new GlmException("GLM 响应 200 但正文为空（视为瞬态）", true);
                        continue;
                    }
                    return content;
                }
                String snippet = truncate(new String(response.body(), StandardCharsets.UTF_8));
                GlmException failure = new GlmException(
                        "GLM HTTP " + status + "：" + snippet, status == 429 || status >= 500);
                if (!failure.isRetryable()) {
                    throw failure;
                }
                lastTransient = failure;
            } catch (IOException e) {
                lastTransient = new GlmException("GLM 请求 IO/超时失败（视为瞬态）：" + e.getMessage(), true, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GlmException("GLM 请求被中断", true, e);
            }
        }
        throw lastTransient != null ? lastTransient : new GlmException("GLM 调用失败：重试耗尽", true);
    }

    private byte[] buildBody(String systemPrompt, JsonNode userContent) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", props.getGlm().getModel());
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").set("content", userContent);
        root.put("temperature", TEMPERATURE);
        root.put("max_tokens", MAX_TOKENS);
        try {
            return mapper.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new GlmException("GLM 请求体序列化失败", false, e);
        }
    }

    /** choices[0].message.content（Jackson 读树）；结构缺失返回 null。 */
    private String parseContent(byte[] body) throws IOException {
        JsonNode content = mapper.readTree(body).path("choices").path(0).path("message").path("content");
        return content.isMissingNode() || content.isNull() ? null : content.asText();
    }

    /** 第 attempt 次（attempt≥2）之前的退避：2s / 4s（指数）。 */
    private void sleepBeforeRetry(int attempt) {
        long backoffMillis = backoffBaseMillis << (attempt - 2);
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GlmException("GLM 重试退避被中断", true, e);
        }
    }

    private static String truncate(String s) {
        return s.length() > ERROR_BODY_SNIPPET ? s.substring(0, ERROR_BODY_SNIPPET) : s;
    }
}
