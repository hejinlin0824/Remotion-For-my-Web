package com.wyf.factory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 驳回/判负错误清单子表（T19a，表 job_review_errors）：每一次「回传重生成的错误清单」
 * 一行一条原因落库，与 Job 一对多。
 *
 * <ul>
 *   <li><b>观测回溯</b>：QA 报告每轮覆写、lastError 只存尾巴——中途驳回原因在此全程可查
 *       （GET /api/v1/jobs/{id}/review-errors）；</li>
 *   <li><b>重启读回</b>：驳回回环中断重启后，最近一份清单从库读回注入重生成
 *       （JobOrchestrator.loadResume，消除 T14a M-1 盲重试降级）。</li>
 * </ul>
 *
 * <p>来源 source：REVIEW（V1-V4 驳回）/ QA（审帧判负）/ MATERIAL / SCRIPT（工位级轻校验）。
 * 轮次 round 为来源内 1-based 序号：REVIEW=第几轮驳回、QA=第几轮判负、MATERIAL/SCRIPT=第几次
 * 生成尝试。一次回传事件 = 同 (jobId, source, round) 的一组行；id 数据库自增，事件时序即 id 序
 * （编排器单 worker 串行推进同一 job，事件不交错）。</p>
 */
@Entity
@Table(name = "job_review_errors")
public class JobReviewError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", length = 36, nullable = false)
    private String jobId;

    @Column(nullable = false)
    private int round;

    @Column(length = 20, nullable = false)
    private String source;

    /** 单条原因（原清单一行原文，不拼接——分轮分条可查） */
    @Lob
    @Column(nullable = false)
    private String reason;

    private LocalDateTime createdAt;

    public JobReviewError() {
    }

    public JobReviewError(String jobId, String source, int round, String reason, LocalDateTime createdAt) {
        this.jobId = jobId;
        this.source = source;
        this.round = round;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public String getJobId() { return jobId; }
    public int getRound() { return round; }
    public String getSource() { return source; }
    public String getReason() { return reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
