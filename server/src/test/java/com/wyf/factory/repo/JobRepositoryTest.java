package com.wyf.factory.repo;

import com.wyf.factory.api.JobService;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.JobStatus;
import com.wyf.factory.domain.StageHistoryEntry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@DataJpaTest
class JobRepositoryTest {

    @Autowired
    TestEntityManager em;

    @Autowired
    JobRepository repo;

    @Autowired
    EntityManagerFactory emf;

    @Test
    @DisplayName("存取往返：@Lob 文本/图片字节 + stageHistory JSON（含 LocalDateTime 纳秒精度）")
    void roundtrip_keepsLobsAndStageHistoryJson() {
        Job job = newJob("TEXT");
        job.setInputText("设 f(x)=x^3+ax^2+x 在 R 上单调递增，求 a 的取值范围");
        job.setAspect("16:9");
        job.setVoice("Cherry");
        job.setResolution("720p");
        job.setCallbackUrl("http://callback.example/cb/1");
        job.setArtifactsDir("artifacts/job-1");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        job.setImageBase64(png);
        job.setLastError("第一轮审题超时（已重试）");
        job.enterStage(JobStatus.EXTRACTING, "审题开始");     // QUEUED → EXTRACTING
        job.enterStage(JobStatus.GENERATING, "内容生成开始"); // EXTRACTING → GENERATING
        LocalDateTime generatingAt = job.getStageHistory().get(2).getAt();
        LocalDateTime extractingAt = job.getStageHistory().get(1).getAt();

        em.persistAndFlush(job);
        em.clear();

        Job loaded = em.find(Job.class, job.getId());
        assertThat(loaded.getStatus()).isEqualTo(JobStatus.GENERATING);
        assertThat(loaded.getStage()).isEqualTo("GENERATING");
        assertThat(loaded.getInputText()).isEqualTo("设 f(x)=x^3+ax^2+x 在 R 上单调递增，求 a 的取值范围");
        assertThat(loaded.getAspect()).isEqualTo("16:9");
        assertThat(loaded.getVoice()).isEqualTo("Cherry");
        assertThat(loaded.getResolution()).isEqualTo("720p");
        assertThat(loaded.getCallbackUrl()).isEqualTo("http://callback.example/cb/1");
        assertThat(loaded.getArtifactsDir()).isEqualTo("artifacts/job-1");
        assertThat(loaded.getImageBase64()).containsExactly(png);
        assertThat(loaded.getLastError()).isEqualTo("第一轮审题超时（已重试）");
        assertThat(loaded.isCancelRequested()).isFalse();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getVersion()).isEqualTo(0L);

        // stageHistory JSON 往返：入队 1 条 + enterStage 2 条
        List<StageHistoryEntry> history = loaded.getStageHistory();
        assertThat(history).hasSize(3);
        assertThat(history.get(0).getStage()).isEqualTo("QUEUED");
        assertThat(history.get(0).getState()).isEqualTo("ENTER");
        assertThat(history.get(1).getStage()).isEqualTo("EXTRACTING");
        assertThat(history.get(1).getState()).isEqualTo("ENTER");
        assertThat(history.get(1).getNote()).isEqualTo("审题开始");
        assertThat(history.get(1).getAt()).isEqualTo(extractingAt);
        assertThat(history.get(2).getStage()).isEqualTo("GENERATING");
        assertThat(history.get(2).getNote()).isEqualTo("内容生成开始");
        assertThat(history.get(2).getAt()).isEqualTo(generatingAt);
    }

    @Test
    @DisplayName("新任务默认：status=QUEUED、id=UUID(36位)、resolution=1080p、重试计数全 0")
    void persist_newJob_defaults() {
        Job job = newJob("IMAGE");
        em.persistAndFlush(job);
        em.clear();

        Job loaded = em.find(Job.class, job.getId());
        assertThat(loaded.getId()).hasSize(36);
        assertThat(loaded.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(loaded.getStage()).isEqualTo("QUEUED");
        assertThat(loaded.getResolution()).isEqualTo("1080p");
        assertThat(loaded.getExtractRetries()).isZero();
        assertThat(loaded.getGenRetries()).isZero();
        assertThat(loaded.getReviewRetries()).isZero();
        assertThat(loaded.getTtsRetries()).isZero();
        assertThat(loaded.getQaRounds()).isZero();
        assertThat(loaded.getStageHistory()).hasSize(1); // 仅入队一条
    }

    @Test
    @DisplayName("findFirstByStatusOrderByCreatedAtAsc：最旧优先，且只取指定状态")
    void findFirstByStatus_returnsOldestOfRequestedStatus() {
        Job older = newJob("TEXT");
        older.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        Job newer = newJob("TEXT"); // createdAt = now
        Job finished = newJob("TEXT");
        finished.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        finished.setStatus(JobStatus.DONE); // 非 QUEUED，不应被领单查询命中

        em.persistAndFlush(finished);
        em.persistAndFlush(older);
        em.persistAndFlush(newer);
        em.clear();

        assertThat(repo.findFirstByStatusOrderByCreatedAtAsc(JobStatus.QUEUED))
                .hasValueSatisfying(j -> assertThat(j.getId()).isEqualTo(older.getId()));
        assertThat(repo.findFirstByStatusOrderByCreatedAtAsc(JobStatus.SPEAKING)).isEmpty();
    }

    @Test
    @DisplayName("findByStatus：分页返回指定状态")
    void findByStatus_paginates() {
        for (int i = 0; i < 3; i++) {
            em.persistAndFlush(newJob("TEXT"));
        }
        Job failed = newJob("IMAGE");
        failed.setStatus(JobStatus.FAILED);
        em.persistAndFlush(failed);
        em.clear();

        Page<Job> page = repo.findByStatus(JobStatus.QUEUED, PageRequest.of(0, 2));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allSatisfy(j -> assertThat(j.getStatus()).isEqualTo(JobStatus.QUEUED));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 摆脱测试事务：先真正提交入库，两个 worker 才看得见这一行
    @DisplayName("乐观锁领单：两个 worker 领同一 QUEUED，恰一个 commit 成功，另一个撞 OptimisticLockException")
    void claim_sameQueuedJobByTwoWorkers_exactlyOneWins() {
        Job job = repo.saveAndFlush(newJob("TEXT"));
        String jobId = job.getId();

        EntityManager worker1 = emf.createEntityManager();
        EntityManager worker2 = emf.createEntityManager();
        try {
            // 两个 worker 各自独立事务加载同一行（都看到 version=0 的 QUEUED）
            worker1.getTransaction().begin();
            worker2.getTransaction().begin();
            Job claim1 = worker1.find(Job.class, jobId);
            Job claim2 = worker2.find(Job.class, jobId);

            // 领单协议：enterStage(EXTRACTING) 后 save
            claim1.enterStage(JobStatus.EXTRACTING, "worker1 抢单");
            worker1.getTransaction().commit(); // 成功，version 0 → 1

            // worker2 还拿着旧快照（内存里仍是 QUEUED，业务规则放行），靠乐观锁在 commit 拦截
            claim2.enterStage(JobStatus.EXTRACTING, "worker2 抢单");
            assertThatThrownBy(worker2.getTransaction()::commit)
                    .satisfies(e -> assertThat(hasOptimisticLockConflict(e))
                            .as("worker2 commit 应因乐观锁冲突失败，实际: %s", e)
                            .isTrue());
        } finally {
            worker1.close();
            worker2.close();
        }

        try {
            // 库内终局：恰一个 worker 成功，失败方没有留下任何痕迹
            Job after = repo.findById(jobId).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(JobStatus.EXTRACTING);
            assertThat(after.getVersion()).isEqualTo(1L);
            assertThat(after.getStageHistory()).hasSize(2); // 入队 + worker1 的 EXTRACTING
            assertThat(after.getStageHistory().get(1).getNote()).isEqualTo("worker1 抢单");
        } finally {
            // 本测试绕过了 @DataJpaTest 的回滚（NOT_SUPPORTED），清理本行避免泄漏进共享测试库
            repo.deleteById(jobId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 真提交入库， rival 事务与 cancel 落库才看得见同一行
    @DisplayName("T15b① 真乐观锁复现（R3 attempt3）：cancel 读改写窗口内编排器并发提交一次 → 恰一次撞乐观锁，修复后重读重试置位成功（修复前 OLE 穿出 → 兜底 500）")
    void cancel_tocTou_racingOrchestratorSave_retriesInsteadOfFailing() {
        Job job = repo.saveAndFlush(newJob("TEXT"));
        job.enterStage(JobStatus.EXTRACTING, "领单");
        job = repo.saveAndFlush(job);
        job.enterStage(JobStatus.GENERATING, "素材+剧本生成开始");
        job = repo.saveAndFlush(job);
        String jobId = job.getId();

        AtomicInteger rivalBumps = new AtomicInteger();
        // Spring Data 代理不可 Mockito.spy（unwrap 失败），用 delegatesTo 包装真实 bean，仅改写 save
        JobRepository racing = mock(JobRepository.class, delegatesTo(repo));
        doAnswer(inv -> {
            if (rivalBumps.compareAndSet(0, 1)) {
                // 模拟编排器 retryOrFail 在 cancel 的 findById 与 save 之间落库恰一次（版本推进）
                EntityManager rival = emf.createEntityManager();
                try {
                    rival.getTransaction().begin();
                    Job fresh = rival.find(Job.class, jobId);
                    fresh.setLastError("GLM 请求 IO/超时失败（视为瞬态）");
                    rival.getTransaction().commit();
                } finally {
                    rival.close();
                }
            }
            return repo.save(inv.getArgument(0));
        }).when(racing).save(any(Job.class));

        JobService.CancelResult result = new JobService(racing,
                mock(com.wyf.factory.repo.JobReviewErrorRepository.class),
                mock(com.wyf.factory.pipeline.JobOrchestrator.class), new AppProperties()).cancel(jobId);

        assertThat(result).isEqualTo(JobService.CancelResult.ACCEPTED);
        assertThat(rivalBumps).hasValue(1);   // 编排器只并发提交一次：撞锁后重读重试恰一次成功
        Job after = repo.findById(jobId).orElseThrow();
        assertThat(after.isCancelRequested()).isTrue();
        assertThat(after.getStatus()).isEqualTo(JobStatus.GENERATING);   // 只置位标记，不改状态
        assertThat(after.getLastError()).isEqualTo("GLM 请求 IO/超时失败（视为瞬态）");   // 编排器写入未被抹掉
        repo.deleteById(jobId); // 绕过 @DataJpaTest 回滚（NOT_SUPPORTED），清理避免泄漏进共享测试库
    }

    @Test
    @DisplayName("T15b②：genDeadlineAt 列持久往返（断点续跑/sweep 后墙钟仍生效的落库前提）")
    void genDeadlineAt_roundtripsThroughDb() {
        Job job = newJob("TEXT");
        job.setGenDeadlineAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusMinutes(30));
        em.persistAndFlush(job);
        em.clear();

        Job loaded = em.find(Job.class, job.getId());
        assertThat(loaded.getGenDeadlineAt()).isEqualTo(job.getGenDeadlineAt());
    }

    @Test
    @DisplayName("T21：processingDeadlineAt 列持久往返（全局墙钟重启读回/sweep 判死的落库前提）")
    void processingDeadlineAt_roundtripsThroughDb() {
        Job job = newJob("TEXT");
        job.setProcessingDeadlineAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusMinutes(60));
        em.persistAndFlush(job);
        em.clear();

        Job loaded = em.find(Job.class, job.getId());
        assertThat(loaded.getProcessingDeadlineAt()).isEqualTo(job.getProcessingDeadlineAt());
    }

    private static boolean hasOptimisticLockConflict(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof OptimisticLockException || c instanceof StaleObjectStateException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static Job newJob(String inputType) {
        Job job = new Job();
        job.setInputType(inputType);
        return job;
    }
}
