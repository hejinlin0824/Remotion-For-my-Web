package com.wyf.factory.repo;

import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.JobStatus;
import jakarta.persistence.OptimisticLockException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 领单协议（供 JobOrchestrator / T10 消费）——乐观锁抢占，不加分布式锁：
 * <ol>
 *   <li>编排器 {@code findFirstByStatusOrderByCreatedAtAsc(JobStatus.QUEUED)} 取出最旧 QUEUED；</li>
 *   <li>{@code job.enterStage(JobStatus.EXTRACTING, note)} 后 {@code save(job)}；</li>
 *   <li>save 时撞 {@link OptimisticLockException} = 行已被其他 worker 抢走，静默跳过本轮。</li>
 * </ol>
 * 抢占由 Job 的 {@code @Version} 乐观锁保证：两个 worker 并发领同一 QUEUED，恰一个 commit 成功。
 */
public interface JobRepository extends JpaRepository<Job, String> {

    /** 最旧的指定状态任务（领单入口，FIFO） */
    Optional<Job> findFirstByStatusOrderByCreatedAtAsc(JobStatus status);

    /** 按状态分页查询（GET /api/v1/jobs?status= 列表） */
    Page<Job> findByStatus(JobStatus status, Pageable pageable);
}
