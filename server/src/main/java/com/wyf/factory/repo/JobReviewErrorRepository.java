package com.wyf.factory.repo;

import com.wyf.factory.domain.JobReviewError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 错误清单子表访问（T19a）。一次回传事件 = 同 (jobId, source, round) 的一组行；
 * 「最近一份清单」= id 最大行所属的事件组（编排器单 worker 串行推进，事件 id 单调递增）。
 */
public interface JobReviewErrorRepository extends JpaRepository<JobReviewError, Long> {

    /** 该 job 最近落库的一行（id 最大 = 最近一次回传事件的末条，仅用于事件定位） */
    Optional<JobReviewError> findTopByJobIdOrderByIdDesc(String jobId);

    /** 定位事件的整组行（按落库顺序 = 原清单顺序，重启读回用） */
    List<JobReviewError> findByJobIdAndSourceAndRoundOrderByIdAsc(String jobId, String source, int round);

    /** 该 job 全部清单行（GET /api/v1/jobs/{id}/review-errors 按 id 升序 = 时间序输出） */
    List<JobReviewError> findByJobIdOrderByIdAsc(String jobId);
}
