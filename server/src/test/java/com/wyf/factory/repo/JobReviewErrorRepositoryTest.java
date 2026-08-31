package com.wyf.factory.repo;

import com.wyf.factory.domain.JobReviewError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误清单子表（T19a）落库契约：@Lob 原因往返 + 「最近一份清单」事件组定位查询。
 * 形状：job_review_errors(job_id, round, source, reason, created_at)，一次回传事件 =
 * 同 (jobId, source, round) 的一组行，id 单调递增即事件时序（单 worker 串行推进）。
 */
@DataJpaTest
class JobReviewErrorRepositoryTest {

    @Autowired
    TestEntityManager em;

    @Autowired
    JobReviewErrorRepository repo;

    @Test
    @DisplayName("存取往返：@Lob 长原因（含换行）与 created_at 精确保留")
    void roundtrip_keepsLobReasonAndCreatedAt() {
        String longReason = "FAIL s03 帧 12 结论卡公式等号后折行。\n".repeat(80) + "行尾锚点-不得截断";
        JobReviewError row = new JobReviewError("job-a", "QA", 2, longReason,
                LocalDateTime.of(2026, 8, 31, 10, 15, 30));

        em.persistAndFlush(row);
        em.clear();

        JobReviewError loaded = em.find(JobReviewError.class, row.getId());
        assertThat(loaded.getJobId()).isEqualTo("job-a");
        assertThat(loaded.getSource()).isEqualTo("QA");
        assertThat(loaded.getRound()).isEqualTo(2);
        assertThat(loaded.getReason()).isEqualTo(longReason);   // @Lob 不截断、换行保留
        assertThat(loaded.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 31, 10, 15, 30));
        assertThat(loaded.getId()).isNotNull();
    }

    @Test
    @DisplayName("createdAt 缺省：@PrePersist 兜底填充")
    void prePersist_fillsCreatedAt() {
        JobReviewError row = new JobReviewError("job-a", "REVIEW", 1, "差异", null);
        em.persistAndFlush(row);
        em.clear();

        assertThat(em.find(JobReviewError.class, row.getId()).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("事件组定位：findTop 取该 job 最近一行（跨 job 隔离）；(source,round) 组按落库序整组读回")
    void eventQueries_locateLatestGroupPerJob() {
        JobReviewError v1a = persist("job-a", "REVIEW", 1, "V1/x: 差异一");
        JobReviewError v1b = persist("job-a", "REVIEW", 1, "V1/y: 差异二");
        JobReviewError qa1 = persist("job-a", "QA", 1, "FAIL s03 折行");
        persist("job-b", "REVIEW", 1, "别家任务的差异");
        em.clear();

        // 最近一行 = id 最大（QA 组），且不串到 job-b
        assertThat(repo.findTopByJobIdOrderByIdDesc("job-a")).hasValueSatisfying(r ->
                assertThat(r.getId()).isEqualTo(qa1.getId()));
        assertThat(repo.findTopByJobIdOrderByIdDesc("job-b")).hasValueSatisfying(r ->
                assertThat(r.getJobId()).isEqualTo("job-b"));
        assertThat(repo.findTopByJobIdOrderByIdDesc("job-c")).isEmpty();

        // 最近事件整组读回：按落库顺序（= 原清单顺序）
        assertThat(repo.findByJobIdAndSourceAndRoundOrderByIdAsc("job-a", "REVIEW", 1))
                .extracting(JobReviewError::getReason).containsExactly("V1/x: 差异一", "V1/y: 差异二");
        assertThat(repo.findByJobIdAndSourceAndRoundOrderByIdAsc("job-a", "QA", 1))
                .extracting(JobReviewError::getId).containsExactly(qa1.getId());
        assertThat(repo.findByJobIdAndSourceAndRoundOrderByIdAsc("job-a", "REVIEW", 2)).isEmpty();

        // 全量：按 id 升序（观测端点的时间序）
        assertThat(repo.findByJobIdOrderByIdAsc("job-a"))
                .extracting(JobReviewError::getId).containsExactly(v1a.getId(), v1b.getId(), qa1.getId());
    }

    private JobReviewError persist(String jobId, String source, int round, String reason) {
        JobReviewError row = new JobReviewError(jobId, source, round, reason, LocalDateTime.now());
        em.persistAndFlush(row);
        return row;
    }
}
