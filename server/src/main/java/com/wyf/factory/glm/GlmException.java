package com.wyf.factory.glm;

/**
 * GLM 工位失败（含密钥未配置、HTTP 错误、瞬态重试耗尽）。
 * retryable=true 表示瞬态（429/5xx/IO/超时/空响应），调用方可整体重试；
 * retryable=false 表示致命（4xx 非 429、key 未配置、序列化失败），重试无意义。
 * 消息只含状态码/来源名/响应体片段，绝不含 key 值。
 */
public class GlmException extends RuntimeException {

    private final boolean retryable;

    public GlmException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public GlmException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
