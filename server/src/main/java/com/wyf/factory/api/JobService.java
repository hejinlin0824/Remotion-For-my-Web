package com.wyf.factory.api;

import com.wyf.factory.api.dto.CreateJobRequest;
import com.wyf.factory.api.dto.JobView;
import com.wyf.factory.api.dto.ReviewErrorView;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.JobStatus;
import com.wyf.factory.pipeline.JobOrchestrator;
import com.wyf.factory.repo.JobRepository;
import com.wyf.factory.repo.JobReviewErrorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * REST 层对 Job 领域的薄封装（计划 Task 3）：入队 / 批量入队 / 查询 / 列表 / 取消 / 视频定位。
 * 校验（inputType/text/imageBase64/aspect/voice）在 Controller 层，这里只做业务判定与落库。
 * T27：confirm/revise 薄委托 {@link JobOrchestrator}（状态迁移+墙钟+驱动单源在编排器）；
 * cancel 对 AWAITING_CONFIRM 就地终态（挂起态无 worker 收割标记）。
 */
@Service
public class JobService {

    /** DELETE /{id} 的语义化结果码，Controller 映射 202/409/200/404 */
    public enum CancelResult { ACCEPTED, NOT_CANCELLABLE, ALREADY_TERMINAL, NOT_FOUND }

    private final JobRepository repo;
    private final JobReviewErrorRepository reviewErrorRepo;
    private final JobOrchestrator orchestrator;
    private final AppProperties props;

    public JobService(JobRepository repo, JobReviewErrorRepository reviewErrorRepo,
                      JobOrchestrator orchestrator, AppProperties props) {
        this.repo = repo;
        this.reviewErrorRepo = reviewErrorRepo;
        this.orchestrator = orchestrator;
        this.props = props;
    }

    /** 入队：新 Job 默认 status=QUEUED、id=UUID（构造器已备），缺省值落库后返回 id。 */
    public String create(CreateJobRequest r) {
        Job job = new Job();
        job.setInputType(r.inputType());
        job.setInputText(r.text());
        if (r.imageBase64() != null && !r.imageBase64().isBlank()) {
            // 存 base64 文本的 UTF-8 字节：EXTRACTING 视觉工位 new String 还原即得原 base64（拼 dataURL 直接用）
            job.setImageBase64(r.imageBase64().getBytes(StandardCharsets.UTF_8));
        }
        job.setAspect(r.aspect() != null ? r.aspect() : "16:9");
        job.setVoice(r.voice() != null ? r.voice() : "Cherry");
        // T17：缺省落 1080p（golden 体系不动）；720p 由 RenderWorker 映射 --scale=2/3 等比出 1280×720
        job.setResolution(r.resolution() != null ? r.resolution() : "1080p");
        job.setCallbackUrl(r.callbackUrl());
        job.setArtifactsDir(props.getArtifactsDir() + "/" + job.getId());
        return repo.save(job).getId();
    }

    /** 批量入队（调用方已整批预校验） */
    public List<String> createBatch(List<CreateJobRequest> items) {
        return items.stream().map(this::create).toList();
    }

    public Optional<JobView> get(String id) {
        return repo.findById(id).map(JobView::from);
    }

    /**
     * 错误清单（T19a）：该 job 全部驳回/判负原因行，按 id 升序（时间序）。
     * job 不存在 → 404（与 get 对齐）；存在但从未被驳回 → 空列表。
     */
    public List<ReviewErrorView> reviewErrors(String id) {
        if (repo.findById(id).isEmpty()) {
            throw new GlobalExceptionHandler.ApiException(404, "job not found");
        }
        return reviewErrorRepo.findByJobIdOrderByIdAsc(id).stream()
                .map(ReviewErrorView::from)
                .toList();
    }

    /** 列表：status 为 null 查全部，否则按状态过滤 */
    public Page<JobView> list(JobStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<Job> result = status == null ? repo.findAll(pageable) : repo.findByStatus(status, pageable);
        return result.map(JobView::from);
    }

    /** cancel 置位落库的并发重试上限与间隔（T15b①）：编排器单次落库毫秒级，5×20ms 实践上必成功。 */
    static final int CANCEL_SAVE_ATTEMPTS = 5;
    static final long CANCEL_RETRY_BACKOFF_MS = 20;

    /**
     * 取消判定（spec §11 修订：取消放宽至 RENDERING/QA，终态幂等；T15b① 并发加固）：
     * <ul>
     *   <li>QUEUED/EXTRACTING/GENERATING/REVIEWING/RENDERING/QA → 置 cancelRequested=true 落库 →
     *       ACCEPTED（202）。RENDERING/QA 只置标记，由编排器在渲染/QA 工位完成后收割
     *       （成片丢弃不入库 + 终态 CANCELLED）；</li>
     *   <li>SPEAKING → NOT_CANCELLABLE（409）：TTS 中途取消会浪费已合成批次，语义上不允许；</li>
     *   <li>DONE/FAILED/CANCELLED → ALREADY_TERMINAL（200 幂等）；</li>
     *   <li>id 不存在 → NOT_FOUND（404）。</li>
     * </ul>
     *
     * <p><b>T15b①（R3 attempt3 现场实证）</b>：GENERATING 僵尸期间编排器 retryOrFail 循环持续落库，
     * 本方法的「读→改→写」与编排器 save 撞 {@code @Version} →
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException} 穿出 →
     * GlobalExceptionHandler 兜底 500，取消标记从未落库。修复：撞锁后重读最新行按最新状态重判
     * （并发窗口内编排器可能已推进到 SPEAKING/终态，按既有语义返回 409/200），有界重试置位，
     * 绝不向上抛；重试耗尽（编排器毫秒级落库下实践不可达）才语义化 503。</p>
     */
    public CancelResult cancel(String id) {
        for (int attempt = 0; attempt < CANCEL_SAVE_ATTEMPTS; attempt++) {
            Optional<Job> found = repo.findById(id);
            if (found.isEmpty()) {
                return CancelResult.NOT_FOUND;
            }
            Job job = found.get();
            switch (job.getStatus()) {
                case QUEUED, EXTRACTING, GENERATING, REVIEWING, RENDERING, QA -> {
                    try {
                        job.setCancelRequested(true);
                        repo.save(job);
                        return CancelResult.ACCEPTED;
                    } catch (ObjectOptimisticLockingFailureException raced) {
                        sleepBeforeCancelRetry();
                    }
                }
                case AWAITING_CONFIRM -> {
                    // T27：挂起态没有 worker 在跑（无人收割 cancelRequested 标记），取消必须就地终态
                    // AWAITING_CONFIRM→CANCELLED，否则标记置位后任务永久悬挂。撞锁重读重判（并发
                    // confirm/revise 先到已续跑 → 按新状态走既有分支）
                    try {
                        job.enterStage(JobStatus.CANCELLED, "用户取消（待确认态）");
                        repo.save(job);
                        return CancelResult.ACCEPTED;
                    } catch (ObjectOptimisticLockingFailureException raced) {
                        sleepBeforeCancelRetry();
                    }
                }
                case SPEAKING -> {
                    return CancelResult.NOT_CANCELLABLE;
                }
                case DONE, FAILED, CANCELLED -> {
                    return CancelResult.ALREADY_TERMINAL;
                }
            }
        }
        throw new GlobalExceptionHandler.ApiException(503, "取消落库与编排器持续并发冲突，请稍后重试");
    }

    private static void sleepBeforeCancelRetry() {
        try {
            Thread.sleep(CANCEL_RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GlobalExceptionHandler.ApiException(503, "取消落库被中断，请重试");
        }
    }

    /**
     * 确认识图结果（T27）：薄委托 {@link JobOrchestrator#confirmAwaiting}——状态迁移、
     * 墙钟重落与「落库即重新驱动」单源在编排器，这里不做第二份判定。
     */
    public JobOrchestrator.ConfirmResult confirm(String id) {
        return orchestrator.confirmAwaiting(id);
    }

    /**
     * 修改识图结果重审（T27）：薄委托 {@link JobOrchestrator#reviseAwaiting}（库内转 TEXT、
     * reviseCount 防刷、重审驱动单源在编排器）。
     */
    public JobOrchestrator.ReviseResult revise(String id, String text) {
        return orchestrator.reviseAwaiting(id, text);
    }

    /**
     * 成片定位（T12 F5 门禁）：仅 status==DONE 放行——QA/重渲期间 artifacts 里的 final.mp4 是
     * 上轮待重判的旧片（T12 实证 job1 在 QA 中返回了第一轮被弃成片），非 DONE 一律
     * 404「成片未定版」（GlobalExceptionHandler 既有 {error} 契约）。
     * DONE 且 artifacts/{id}/final.mp4 已落盘才返回路径；任务不存在或文件缺失 → empty（404 video 未就绪）。
     */
    public Optional<Path> videoPath(String id) {
        return repo.findById(id).flatMap(job -> {
            if (job.getStatus() != JobStatus.DONE) {
                throw new GlobalExceptionHandler.ApiException(404, "成片未定版：任务 " + job.getStatus() + " 未达 DONE");
            }
            String dir = job.getArtifactsDir() != null
                    ? job.getArtifactsDir()
                    : props.getArtifactsDir() + "/" + job.getId();
            Path mp4 = Path.of(dir, "final.mp4");
            return Files.exists(mp4) ? Optional.of(mp4) : Optional.empty();
        });
    }

    /**
     * 识图结果定位（T26，模式对齐 {@link #videoPath}）：artifacts/{id}/extracted.json 已落盘 → 路径，
     * 只读展示（前端识图卡片 KaTeX 可视化 + 修改后重测）。
     * 任务不存在 → 404 job not found；未落盘（QUEUED/EXTRACTING 中/落盘失败）→ 404 识图结果未生成。
     * 无 DONE 门禁（与 videoPath 刻意不同）：T26 起 artifacts 保留白名单扩为三件套
     * （final.mp4 + audio/lines + extracted.json），FAILED/CANCELLED 也保留，失败同样能回看识图内容。
     */
    public Path extractedJson(String id) {
        Job job = repo.findById(id).orElseThrow(() -> new GlobalExceptionHandler.ApiException(404, "job not found"));
        String dir = job.getArtifactsDir() != null
                ? job.getArtifactsDir()
                : props.getArtifactsDir() + "/" + job.getId();
        Path json = Path.of(dir, "extracted.json");
        if (!Files.isRegularFile(json)) {
            throw new GlobalExceptionHandler.ApiException(404, "识图结果未生成");
        }
        return json;
    }
}
