package com.wyf.factory.api.dto;

import com.wyf.factory.domain.JobReviewError;

import java.time.LocalDateTime;

/**
 * GET /api/v1/jobs/{id}/review-errors 条目视图（T19a）：一行一条驳回/判负原因，
 * 按 id 升序（时间序）输出。独立端点而非 JobView 字段——清单多轮多条可能很长。
 */
public record ReviewErrorView(String jobId, int round, String source, String reason, LocalDateTime createdAt) {

    public static ReviewErrorView from(JobReviewError e) {
        return new ReviewErrorView(e.getJobId(), e.getRound(), e.getSource(), e.getReason(), e.getCreatedAt());
    }
}
