package com.wyf.factory.stations;

import com.wyf.factory.glm.GlmException;

import java.util.List;

/**
 * 分片生成失败（T18）：包装分片名 + 可落库的错误清单，供编排器把
 * job_review_errors 的 source 标注为分片路由结果（P0/P1/P2/P3:act3-a…）。
 *
 * <p>产生途径：分片 GLM 调用抛 {@link GlmException}（轻校验清单 problems 结构化透传，
 * message 保持既有 join("\n") 形状）；或分片校验/合并器校验直接产出清单。
 * retryable 语义沿用 GlmException；编排器预算判定与既有 retryOrFail 通道不变。</p>
 */
public class ShardGenException extends RuntimeException {

    /** 失败分片名（落库 source）：P0/P1/P2/P3:act2/P3:act3-a/…/MERGE/GEN。 */
    private final String shard;
    private final boolean retryable;
    /** 轻校验差异清单（逐条）；非清单失败（瞬态/致命）为 null。 */
    private final transient List<String> problems;

    /** 分片 GLM 调用失败包装（清单/可重试性从 GlmException 透传）。 */
    public ShardGenException(String shard, GlmException cause) {
        super(shard + " 分片失败：" + cause.getMessage(), cause);
        this.shard = shard;
        this.retryable = cause.isRetryable();
        this.problems = cause.getProblems();
    }

    /** 分片校验/合并校验失败专用：problems 逐条差异即回传重试的错误清单（工位级校验恒可重试）。 */
    public ShardGenException(String shard, List<String> problems, Throwable cause) {
        super(shard + " 分片校验失败（" + problems.size() + " 条差异）：\n" + String.join("\n", problems), cause);
        this.shard = shard;
        this.retryable = true;
        this.problems = List.copyOf(problems);
    }

    public String getShard() {
        return shard;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 轻校验差异清单；null = 非清单失败（瞬态/致命），不可当清单落库。 */
    public List<String> getProblems() {
        return problems;
    }
}
