package com.wyf.factory.glm;

import java.util.List;

/**
 * GLM 工位失败（含密钥未配置、HTTP 错误、瞬态重试耗尽）。
 * retryable=true 表示瞬态（429/5xx/IO/超时/空响应），调用方可整体重试；
 * retryable=false 表示致命（4xx 非 429、key 未配置、序列化失败），重试无意义。
 * 消息只含状态码/来源名/响应体片段，绝不含 key 值。
 *
 * <p>T19a：工位级轻校验失败（MaterialStation/ScriptStation 的绑定校验）经
 * {@link #GlmException(List, boolean)} 构造——差异清单以结构化 {@code problems} 携带
 * （message 仍为 join("\n") 保持既有形状），编排器落库/回传免从 message 反解
 * （"不是 JSON" 原始响应片段等自由文本消息不会被误当清单解析）。</p>
 */
public class GlmException extends RuntimeException {

    private final boolean retryable;
    /** 工位级轻校验差异清单（逐条）；非轻校验失败为 null。 */
    private final transient List<String> problems;

    public GlmException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
        this.problems = null;
    }

    public GlmException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.problems = null;
    }

    /** 工位级轻校验失败专用：problems 逐条差异即回传重试的错误清单，message 保持 join("\n") 既有形状。 */
    public GlmException(List<String> problems, boolean retryable) {
        super(String.join("\n", problems));
        this.retryable = retryable;
        this.problems = List.copyOf(problems);
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 轻校验差异清单；null = 非轻校验失败（瞬态/致命/原始响应坏输出），不可当清单用。 */
    public List<String> getProblems() {
        return problems;
    }
}
