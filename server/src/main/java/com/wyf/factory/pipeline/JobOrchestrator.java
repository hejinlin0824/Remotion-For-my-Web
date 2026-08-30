package com.wyf.factory.pipeline;

import com.wyf.factory.config.AppProperties;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.JobStatus;
import com.wyf.factory.glm.GlmException;
import com.wyf.factory.render.QaFrameCheck;
import com.wyf.factory.render.RenderWorker;
import com.wyf.factory.render.WorkspaceManager;
import com.wyf.factory.repo.JobRepository;
import com.wyf.factory.stations.ExtractResult;
import com.wyf.factory.stations.ExtractStation;
import com.wyf.factory.stations.Material;
import com.wyf.factory.stations.MaterialStation;
import com.wyf.factory.stations.ScriptStation;
import com.wyf.factory.tts.AudioMeta;
import com.wyf.factory.tts.TtsPipeline;
import com.wyf.factory.validate.V1Structural;
import com.wyf.factory.validate.V2Fidelity;
import com.wyf.factory.validate.V3Refs;
import com.wyf.factory.validate.V4Judge;
import com.wyf.factory.validate.ValidationContext;
import com.wyf.factory.validate.ValidationResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * 编排器（plan Task 10 + Ruling-18 QA 前置）：领单 → 状态机推进 → 终态收尾。
 *
 * <p>阶段序（Ruling-18，2026-08-30）：QUEUED→EXTRACTING→GENERATING→REVIEWING→SPEAKING→
 * <b>QA（still 预审）→RENDERING→DONE</b>——审帧帧本就由 qa_stills 从 composition 直接 renderStill
 * （不依赖成片视频），pick_frames 帧号只依赖 TTS 后 timeline，TTS 完成即具备 QA 全部输入；
 * 驳回循环不再白付整片渲染。渲染成功即 DONE，<b>渲染后不再有 QA 轮</b>。</p>
 *
 * <ul>
 *   <li><b>领单</b>（{@link JobRepository} javadoc 协议）：{@link #poll()} 查最旧 QUEUED →
 *       enterStage(EXTRACTING) 后 save；save 撞 {@link ObjectOptimisticLockingFailureException}
 *       = 被其他 worker 抢走，静默 return。抢到后提交专用线程池（核心=最大=4）跑 {@link #process}。</li>
 *   <li><b>断点续跑</b>：process 按 DB 当前 status 从断点阶段继续；
 *       workspace/{jobId}/src/data/content.json 存在 → 跳过 EXTRACTING/GENERATING（ContentJson.readFrom 读回）；
 *       audio_meta.json 存在且 lines wav 数齐 → 跳过 SPEAKING（AudioMeta.readFrom + wav 读回内存）；
 *       content 来自盘上续跑且工作区在盘 → QA 原地复用工作区（out/qa 产物保留，三工具就地覆写重审）；
 *       artifacts/{jobId}/final.mp4 存在 → 渲染已完成，直接 DONE 归档（渲染后无 QA 轮）。
 *       ExtractResult 不可恢复时 V2 以 content.json problem 段为基准（历史 note 记录）。</li>
 *   <li><b>取消</b>：每阶段开始前重读最新 Job 查 cancelRequested；SPEAKING 前可取消
 *       （enterStage CANCELLED + workspace 清理 + 不产 artifacts + 回调）；渲染中不打断（spec §11）。</li>
 *   <li><b>TOCTOU 兜底</b>（T3 评审 M 项）：任何 save 撞乐观锁（如 cancel 并发落库）→ 重读一次
 *       以库内最新状态继续循环，绝不向上抛 500。</li>
 *   <li><b>失败分类</b>（spec §14）：校验驳回 / QA 判负（Ruling-17：带 FAIL 清单回 GENERATING
 *       重生成，Ruling-18 后发生在渲染之前）/ retryable GlmException / retryable RenderException
 *       → 预算内重试（reviewRetries、genRetries、extractRetries、qaRounds）；
 *       FatalExtractException / TtsFatalException / 预算尽 → FAILED（errorMessage + lastError=堆栈尾
 *       2000 字符 + workspace 保留 + 回调）。</li>
 *   <li><b>DONE</b>（渲染收尾）：artifactsDir 以绝对路径落库（GET /video 按 artifactsDir 直读 final.mp4，
 *       与 JobService.videoPath 对齐）、workspace 清理（artifacts 保留）、可选回调。</li>
 * </ul>
 */
@Component
public class JobOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(JobOrchestrator.class);

    /** 与 @Scheduled(fixedDelay) 注解值一致（文档化常量，供配置对照）。 */
    static final long POLL_FIXED_DELAY_MILLIS = 2000L;
    /** 处理线程池规模（brief：核心=最大=4）。 */
    static final int WORKER_POOL_SIZE = 4;
    /** lastError 落库的堆栈尾长度。 */
    static final int LAST_ERROR_TAIL = 2000;
    /** IMAGE 路径送 GLM 视觉通道的 mime（入队校验仅收 base64，统一按 PNG 提交）。 */
    private static final String IMAGE_MIME = "image/png";

    private final JobRepository repo;
    private final ExtractStation extractStation;
    private final MaterialStation materialStation;
    private final ScriptStation scriptStation;
    private final V1Structural v1;
    private final V2Fidelity v2;
    private final V3Refs v3;
    private final V4Judge v4;
    private final TtsPipeline ttsPipeline;
    private final WorkspaceManager workspaceManager;
    private final RenderWorker renderWorker;
    private final QaFrameCheck qaFrameCheck;
    private final ResourceSemaphores semaphores;
    private final CallbackClient callbackClient;
    private final AppProperties props;

    /** 任务处理执行器：Spring 下 @PostConstruct 建 ThreadPoolTaskExecutor；测试可注入替身。 */
    private Executor jobExecutor;

    public JobOrchestrator(JobRepository repo,
                           ExtractStation extractStation,
                           MaterialStation materialStation,
                           ScriptStation scriptStation,
                           V1Structural v1,
                           V2Fidelity v2,
                           V3Refs v3,
                           V4Judge v4,
                           TtsPipeline ttsPipeline,
                           WorkspaceManager workspaceManager,
                           RenderWorker renderWorker,
                           QaFrameCheck qaFrameCheck,
                           ResourceSemaphores semaphores,
                           CallbackClient callbackClient,
                           AppProperties props) {
        this.repo = repo;
        this.extractStation = extractStation;
        this.materialStation = materialStation;
        this.scriptStation = scriptStation;
        this.v1 = v1;
        this.v2 = v2;
        this.v3 = v3;
        this.v4 = v4;
        this.ttsPipeline = ttsPipeline;
        this.workspaceManager = workspaceManager;
        this.renderWorker = renderWorker;
        this.qaFrameCheck = qaFrameCheck;
        this.semaphores = semaphores;
        this.callbackClient = callbackClient;
        this.props = props;
    }

    @PostConstruct
    void start() {
        if (jobExecutor == null) {
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor pool =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
            pool.setCorePoolSize(WORKER_POOL_SIZE);
            pool.setMaxPoolSize(WORKER_POOL_SIZE);
            pool.setThreadNamePrefix("job-worker-");
            pool.initialize();
            jobExecutor = pool;
        }
    }

    @PreDestroy
    void stop() {
        if (jobExecutor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor pool) {
            pool.shutdown();
        }
        jobExecutor = null;
    }

    /** 测试钩子：替换执行器（同步直跑/收集型），不起真线程。 */
    void setJobExecutor(Executor executor) {
        this.jobExecutor = executor;
    }

    // ------------------------------------------------------------------ 领单

    /** 每 2s 查最旧 QUEUED，CAS 抢占（撞乐观锁=被抢走，静默跳过）后异步推进。 */
    @Scheduled(fixedDelay = POLL_FIXED_DELAY_MILLIS)
    public void poll() {
        Optional<Job> next = repo.findFirstByStatusOrderByCreatedAtAsc(JobStatus.QUEUED);
        if (next.isEmpty()) {
            return;
        }
        Job job = next.get();
        try {
            job.enterStage(JobStatus.EXTRACTING, "领单：开始处理");
            repo.save(job);
        } catch (ObjectOptimisticLockingFailureException raced) {
            return;   // 领单协议：已被其他 worker 抢走
        }
        log.info("job={} 领单成功 → EXTRACTING", job.getId());
        submit(job.getId());
    }

    /**
     * 启动 sweep（修复轮 M1）：服务（重启）就绪后把全部非终态任务重新提交推进，
     * 与 workspace/artifacts 断点产物协同实现断点续跑。
     * QUEUED 不在此提交——那是 poll 的领单范围（2s 内被领），重复提交会双跑同一单。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterrupted() {
        List<Job> unfinished = repo.findByStatusNotIn(
                List.of(JobStatus.DONE, JobStatus.FAILED, JobStatus.CANCELLED));
        int resumed = 0;
        for (Job job : unfinished) {
            // QUEUED 留给 poll 领单（重复提交会双跑）；终态防御性跳过（查询本不该返回）
            if (job.getStatus() == JobStatus.QUEUED || job.getStatus().isTerminal()) {
                continue;
            }
            log.info("job={} 启动续跑（status={}）", job.getId(), job.getStatus());
            submit(job.getId());
            resumed++;
        }
        log.info("启动 sweep：非终态任务 {} 个，续跑提交 {} 个", unfinished.size(), resumed);
    }

    /** 单任务的一条处理线程（poll 领单后提交；执行器缺位时同步兜底，绝不让任务悬挂）。 */
    private void submit(String jobId) {
        Executor executor = this.jobExecutor;
        if (executor == null) {
            process(jobId);
            return;
        }
        executor.execute(() -> {
            try {
                process(jobId);
            } catch (RuntimeException e) {
                log.error("job={} 推进线程抛出未捕获异常", jobId, e);
            }
        });
    }

    // ------------------------------------------------------------------ 主流程

    /**
     * 全流程推进：按 DB 当前 status 从断点阶段继续（服务重启安全，可从任意非终态进入；
     * 启动入口见 {@link #resumeInterrupted()}）。
     * 阶段间每轮重读最新行做取消检查点；任何未分类异常兜底转 FAILED，绝不让任务悬挂非终态。
     */
    public void process(String id) {
        Job job = repo.findById(id).orElse(null);
        if (job == null) {
            log.warn("job={} 不存在，跳过推进", id);
            return;
        }
        Ctx ctx = loadResume(job);
        try {
            loop(job, ctx);
        } catch (InterruptedException interrupted) {
            // 修复轮 I4：中断（executor 关闭/停机）不置 FAILED——保持当前阶段状态原样，
            // 恢复中断标志后安全返回；启动 sweep 重启后从断点自然续跑
            Thread.currentThread().interrupt();
            log.warn("job={} 推进被中断，保持状态 {} 等待断点续跑", id, job.getStatus(), interrupted);
        } catch (RuntimeException unclassified) {
            Job fresh = repo.findById(id).orElse(null);
            if (fresh != null && !fresh.getStatus().isTerminal()) {
                String message = unclassified.getMessage() != null
                        ? unclassified.getMessage()
                        : unclassified.getClass().getSimpleName();
                failJob(fresh, "未分类失败：" + message, unclassified, ctx);
                fireCallback(ctx);
            } else {
                log.error("job={} 推进异常（已终态或行缺失，仅记录）", id, unclassified);
            }
        }
    }

    private void loop(Job job, Ctx ctx) throws InterruptedException {
        while (!job.getStatus().isTerminal()) {
            Job fresh = repo.findById(job.getId()).orElse(null);
            if (fresh == null) {
                log.warn("job={} 行消失（被删除），停止推进", job.getId());
                fireCallback(ctx);
                return;
            }
            job = fresh;
            // 取消检查点（每阶段开始前）：SPEAKING 前阶段间可取消；
            // RENDERING/QA 完成后的检查点在 doRendering/doQa 内（M2）
            if (job.isCancelRequested() && JobStatus.canTransit(job.getStatus(), JobStatus.CANCELLED)) {
                job = doCancel(job, ctx);
                if (job == null || job.getStatus().isTerminal()) {
                    fireCallback(ctx);
                    return;
                }
                continue;
            }
            switch (job.getStatus()) {
                case QUEUED -> job = enterAndSave(job, JobStatus.EXTRACTING, "编排器接管（未经 poll 领单），开始审题");
                case EXTRACTING -> job = doExtracting(job, ctx);
                case GENERATING -> job = doGenerating(job, ctx);
                case REVIEWING -> job = doReviewing(job, ctx);
                case SPEAKING -> job = doSpeaking(job, ctx);
                case RENDERING -> job = doRendering(job, ctx);
                case QA -> job = doQa(job, ctx);
                default -> {
                    fireCallback(ctx);
                    return;
                }
            }
            if (job == null) {
                fireCallback(ctx);
                return;
            }
        }
        fireCallback(ctx);   // 循环条件退出（已是终态）——补发挂起回调
    }

    // ------------------------------------------------------------------ 阶段：EXTRACTING

    private Job doExtracting(Job job, Ctx ctx) {
        if (ctx.contentResumed) {
            return enterGenerating(job, "断点续跑：content.json 已在盘，跳过审题");
        }
        if (ctx.extracted != null) {
            return enterGenerating(job, "审题产物已在手，直接进入生成");
        }
        try {
            ExtractResult extracted = semaphores.withGlm(() -> "IMAGE".equals(job.getInputType())
                    ? extractStation.extractImage(imagePayload(job), IMAGE_MIME)
                    : extractStation.extract(job.getInputText()));
            ctx.extracted = extracted;
            return enterGenerating(job, "审题完成（" + extracted.problemType() + "）");
        } catch (ExtractStation.FatalExtractException fatal) {
            // 读不出题/非数学题：同素材重试无意义（spec §9），直接判死
            return failJob(job, fatal.getMessage(), fatal, ctx);
        } catch (GlmException e) {
            return retryOrFail(job, e, "审题", job.getExtractRetries(), job::setExtractRetries, ctx);
        }
    }

    // ------------------------------------------------------------------ 阶段：GENERATING

    /**
     * （重）进入 GENERATING（T15b②）：每次进入都（重新）落库墙钟死线 genDeadlineAt = now + 配置值
     * （默认 30min）。合法驳回回环（V/QA 判负→GENERATING）重进即刷新——每轮 GENERATING 自得全额；
     * 死线随 restart 持久（sweep 续跑从库读回仍生效）。EXTRACTING 首轮/未进过 GENERATING 的行为
     * NULL=无死线，按既有计数逻辑。
     */
    private Job enterGenerating(Job job, String note) {
        job.setGenDeadlineAt(LocalDateTime.now().plusMinutes(props.getRetry().getGenDeadlineMinutes()));
        return enterAndSave(job, JobStatus.GENERATING, note);
    }

    /** 同 {@link #enterGenerating}，但返回库内最新状态（QA 判负路由用）。 */
    private Job enterGeneratingAndReturn(Job job, String note) {
        job.setGenDeadlineAt(LocalDateTime.now().plusMinutes(props.getRetry().getGenDeadlineMinutes()));
        return enterAndSaveAndReturn(job, JobStatus.GENERATING, note);
    }

    private Job doGenerating(Job job, Ctx ctx) {
        if (ctx.contentResumed) {
            // 只有"从盘上恢复"才跳过；上一轮 REVIEWING 驳回生成的 ctx.content 不算（必须重生成）
            return enterAndSave(job, JobStatus.REVIEWING, "断点续跑：content.json 已在盘，跳过素材/剧本生成");
        }
        List<String> errors = ctx.reviewErrors;
        try {
            Material material = semaphores.withGlm(() -> materialStation.generate(ctx.extracted, errors));
            ContentJson content = semaphores.withGlm(() -> scriptStation.assemble(ctx.extracted, material, errors));
            ctx.content = content;
            return enterAndSave(job, JobStatus.REVIEWING, errors.isEmpty()
                    ? "素材+剧本生成完成"
                    : "驳回重生成完成（错误清单已回传，共 " + errors.size() + " 条）");
        } catch (GlmException e) {
            return retryOrFail(job, e, "内容生成", job.getGenRetries(), job::setGenRetries, ctx);
        }
    }

    // ------------------------------------------------------------------ 阶段：REVIEWING

    private Job doReviewing(Job job, Ctx ctx) {
        if (ctx.content == null) {
            return failJob(job, "REVIEWING 时 content 缺失（内部状态不一致）", null, ctx);
        }
        if (ctx.extracted == null) {
            // 断点续跑：ExtractResult 不可恢复，V2 以 content.json problem 段为基准（记录 note）
            ctx.extracted = extractedFromProblem(ctx.content);
            ctx.extractedFromResume = true;
        }
        ValidationResult merged;
        try {
            ValidationContext vctx = new ValidationContext(ctx.content, ctx.extracted);
            ValidationResult r1 = v1.validate(vctx);
            ValidationResult r2 = v2.validate(vctx);
            ValidationResult r3 = v3.validate(vctx);
            ValidationResult r4 = semaphores.withGlm(() -> v4.validate(vctx));
            merged = ValidationResult.merge(r1, r2, r3, r4);
        } catch (GlmException e) {
            return retryOrFail(job, e, "V4 语义审核", job.getReviewRetries(), job::setReviewRetries, ctx);
        }
        if (merged.pass()) {
            return enterAndSave(job, JobStatus.SPEAKING, ctx.extractedFromResume
                    ? "校验通过（断点续跑：V2 以 content.json problem 段为基准）"
                    : "V1-V4 校验通过");
        }
        // 驳回：错误清单回传 GENERATING（预算内）或判死（预算尽）
        List<String> errors = new ArrayList<>(merged.errors());
        if (errors.isEmpty()) {
            // T7 评审 I 项兜底：V4 REJECT 零理由时补通用理由，避免空清单重试
            errors.add("V4 语义审核驳回（模型未给出具体理由）");
        }
        errors.addAll(merged.softErrors());   // 软预警随清单披露（不参与 pass 判定）
        int used = job.getReviewRetries();
        if (used < props.getRetry().getContentMax()) {
            job.setReviewRetries(used + 1);
            ctx.reviewErrors = List.copyOf(errors);
            // 修复轮 I1：清除断点续跑标记——下一轮 GENERATING 必须真正重生成，
            // 否则续跑场景下 ctx.content 恒非空会被误判为"已在盘"而永不重生成
            ctx.contentResumed = false;
            job.setLastError(tail(String.join("; ", errors), LAST_ERROR_TAIL));
            return enterGenerating(job,
                    "V1-V4 驳回（第 " + (used + 1) + " 轮），错误清单回传重生成");
        }
        return failJob(job, "内容校验连续驳回 " + used + " 轮未过：" + String.join("; ", errors), null, ctx);
    }

    // ------------------------------------------------------------------ 阶段：SPEAKING

    private Job doSpeaking(Job job, Ctx ctx) {
        if (ctx.audioMeta != null) {
            return enterAndSave(job, JobStatus.QA, "断点续跑：audio_meta.json 与台词 wav 已在盘，跳过 TTS");
        }
        final Path staging;
        try {
            staging = Files.createTempDirectory("tts-lines-" + job.getId());
        } catch (IOException e) {
            throw new UncheckedIOException("TTS 暂存目录创建失败", e);
        }
        try {
            AudioMeta meta = semaphores.withTts(() -> ttsPipeline.synthesizeAll(ctx.content, staging));
            ctx.audioMeta = meta;
            ctx.lineWavs = readLineWavs(staging, meta.getLines().size());
            // Ruling-18：TTS 完成 → 时间轴（pick_frames 输入）齐备，直接进 QA still 预审（渲染之前）
            return enterAndSave(job, JobStatus.QA, "TTS 完成（" + meta.getLines().size() + " 句），进入 QA 预审");
        } catch (TtsPipeline.TtsFatalException fatal) {
            // 某句全尝试 + 整批重录仍失败（Global Constraint 3），重试无意义
            return failJob(job, fatal.getMessage(), fatal, ctx);
        } finally {
            deleteQuietly(staging);
        }
    }

    // ------------------------------------------------------------------ 阶段：RENDERING

    /**
     * 渲染（Ruling-18：全链只走一次，成功即 DONE——渲染后无 QA 轮）。
     * 正常链路工作区已由 QA 预审建好（ctx.workspace），此处原地复用不重建；
     * 仅续跑直达 RENDERING（本轮未跑 QA）时现建。
     */
    private Job doRendering(Job job, Ctx ctx) throws InterruptedException {
        if (Files.isRegularFile(artifactsFinal(job.getId()))) {
            // 渲染已完成但 DONE 未落库的窗口期中断 → 直接归档收尾，不重渲也不再 QA
            return finishDone(job, ctx, "断点续跑：final.mp4 已在盘，跳过渲染，成片归档");
        }
        // 协作者声明 InterruptedException，无法进 Supplier：手动 acquire/release（一次一闸，无嵌套）。
        // 渲染进行中不打断（spec §11）；完成后的取消检查点在闸外（M2）
        if (ctx.workspace == null) {
            ctx.workspace = workspaceManager.create(job.getId(), ctx.content, ctx.audioMeta, ctx.lineWavs);
        }
        semaphores.render().acquire();
        try {
            // 全片渲染（不复用短渲）；T17：渲染档位从落库实体读（断点续跑/重渲一致），
            // 库内历史 NULL 行由 RenderWorker 容错按 1080p 处理
            Path mp4 = renderWorker.render(ctx.workspace, job.getResolution());
            ctx.artifactsDir = mp4.getParent();
        } catch (RenderWorker.RenderException e) {
            // failJob 只落库+挂起回调，notify 在 loop 层（闸外）执行——I3
            return renderRetryOrFail(job, e, ctx);
        } finally {
            semaphores.render().release();
        }
        if (job.isCancelRequested()) {
            return cancelAfterWork(job, ctx);   // M2：渲染完成后检查点 → CANCELLED + 成片丢弃
        }
        return finishDone(job, ctx, "渲染完成，成片归档");
    }

    // ------------------------------------------------------------------ 阶段：QA

    /**
     * QA still 预审（Ruling-18：渲染之前）——pick_frames + qa_stills + qa_glm 三步原样复用
     * （三工具零改动，本就不依赖成片视频）。首进 QA 时渲染未发生，审帧工作区在此现建；
     * 断点续跑且 content 来自盘上 → 原地复用工作区（out/qa 产物保留，三工具就地覆写重审，
     * 沿用现 QA 续跑语义：不跳过重审，只复用工作区与产物目录）。
     */
    private Job doQa(Job job, Ctx ctx) throws InterruptedException {
        if (ctx.workspace == null) {
            Path existing = workspaceManager.workspacePath(job.getId());
            ctx.workspace = ctx.contentResumed && existing != null && Files.isDirectory(existing)
                    ? existing
                    : workspaceManager.create(job.getId(), ctx.content, ctx.audioMeta, ctx.lineWavs);
        }
        semaphores.qa().acquire();
        QaFrameCheck.QaResult result;
        try {
            result = qaFrameCheck.check(ctx.workspace);
        } catch (RenderWorker.RenderException e) {
            return renderRetryOrFail(job, e, ctx);
        } finally {
            semaphores.qa().release();
        }
        if (job.isCancelRequested()) {
            return cancelAfterWork(job, ctx);   // M2：预审完成后检查点（即使审帧通过也不渲染）
        }
        if (result.pass()) {
            return enterAndSave(job, JobStatus.RENDERING,
                    "QA 预审通过（" + result.framesChecked() + " 帧），进入渲染");
        }
        return qaRetryOrFail(job, result, ctx);
    }

    // ------------------------------------------------------------------ 终态收尾

    private Job finishDone(Job job, Ctx ctx, String note) {
        Path artifacts = ctx.artifactsDir != null
                ? ctx.artifactsDir
                : Path.of(props.getArtifactsDir()).resolve(job.getId());
        // 绝对路径落库：JobService.videoPath 直接 Path.of(artifactsDir, "final.mp4") 读文件
        job.setArtifactsDir(artifacts.toAbsolutePath().normalize().toString());
        job.enterStage(JobStatus.DONE, note);
        saveTolerant(job);
        cleanupWorkspaceQuietly(job.getId());
        deferCallback(job, JobStatus.DONE, null, ctx);   // I3：闸外发（loop 层）
        return null;
    }

    private Job doCancel(Job job, Ctx ctx) {
        JobStatus before = job.getStatus();
        log.info("job={} 取消生效（原阶段 {}）", job.getId(), job.getStage());
        job.enterStage(JobStatus.CANCELLED, "用户取消");
        Job saved = saveTolerant(job);
        if (saved == null) {
            return null;
        }
        if (saved.getStatus() != JobStatus.CANCELLED) {
            return saved;   // TOCTOU：CANCELLED 写入被并发落库顶掉 → 以库内最新状态继续，下轮检查点再来
        }
        if (before == JobStatus.RENDERING || before == JobStatus.QA) {
            discardArtifactsQuietly(job.getId());   // M2：渲染阶段取消 → 丢弃成片（QA 阶段渲染未发生，deleteIfExists 兜底）
        }
        cleanupWorkspaceQuietly(job.getId());
        deferCallback(job, JobStatus.CANCELLED, null, ctx);
        return null;
    }

    /**
     * 渲染/QA 完成后的取消检查点（修复轮 M2）：渲染路径成片已产出 → 丢弃不入 artifacts
     * （删除 artifacts/{jobId}/final.mp4）；QA 路径（Ruling-18：预审先于渲染）无成片可丢，
     * 仅清理工作区；统一回调 CANCELLED。
     */
    private Job cancelAfterWork(Job job, Ctx ctx) {
        log.info("job={} 渲染/预审完成发现取消标记 → CANCELLED", job.getId());
        job.enterStage(JobStatus.CANCELLED, "用户取消（渲染/QA 完成后检查点）");
        Job saved = saveTolerant(job);
        if (saved == null) {
            return null;
        }
        if (saved.getStatus() != JobStatus.CANCELLED) {
            return saved;   // TOCTOU → 循环以最新状态重查
        }
        discardArtifactsQuietly(job.getId());
        cleanupWorkspaceQuietly(job.getId());
        deferCallback(job, JobStatus.CANCELLED, null, ctx);
        return null;
    }

    private Job failJob(Job job, String message, Throwable cause, Ctx ctx) {
        log.warn("job={} → FAILED：{}", job.getId(), message);
        job.setErrorMessage(message);
        job.setLastError(cause != null ? tail(stackTraceOf(cause), LAST_ERROR_TAIL)
                : tail(String.valueOf(message), LAST_ERROR_TAIL));
        job.enterStage(JobStatus.FAILED, brief(message));
        saveTolerant(job);
        deferCallback(job, JobStatus.FAILED, message, ctx);
        return null;
    }

    // ------------------------------------------------------------------ 重试判定

    /**
     * 可重试异常的预算判定：先查 GENERATING 墙钟死线（T15b②），超线无视剩余次数直接判死；
     * 未超线按既有计数逻辑——未到上限就地重试（留在原状态，循环重跑本阶段），到顶 FAILED。
     * 阶段内瞬态退避已由 glm/tts 客户端内置，这里只管跨尝试预算。
     *
     * <p><b>墙钟动机（R3 attempt3 实证）</b>：跨尝试计数与 GLM 客户端内部有界重试相乘，
     * 网络病态时两个有界=无界墙钟，job GENERATING 僵尸 14h+ 不终态。死线在进 GENERATING
     * 时落库（{@link #enterGenerating}），restart/sweep 续跑后仍生效；驳回回环重进刷新。</p>
     */
    private Job retryOrFail(Job job, RuntimeException e, String stage, int used, IntConsumer setUsed, Ctx ctx) {
        LocalDateTime deadline = job.getGenDeadlineAt();
        if (deadline != null && LocalDateTime.now().isAfter(deadline)) {
            String message = stage + "重试墙钟超限（死线 " + deadline + "，已用 " + used
                    + "/" + props.getRetry().getContentMax() + "）：" + e.getMessage();
            return failJob(job, message, null, ctx);   // cause=null → lastError=message（注明墙钟超限）
        }
        int max = props.getRetry().getContentMax();
        if (used < max) {
            setUsed.accept(used + 1);
            job.setLastError(tail(String.valueOf(e.getMessage()), LAST_ERROR_TAIL));
            saveTolerant(job);
            return job;
        }
        return failJob(job, stage + "重试 " + used + " 次预算耗尽：" + e.getMessage(), e, ctx);
    }

    /**
     * 渲染/审帧链失败预算：与 QA 轮次共用 qaRounds（画面类失败与审帧同属"渲染-预审"循环，
     * 上限 app.qa.maxRounds）。RENDERING 中就地重渲；QA 中（Ruling-18）就地重审——
     * 审帧链异常发生在渲染之前，「回退重渲」路由已废弃：渲染成功即 DONE，回退会让
     * 未审成片直达 DONE。两条就地分支均不发生状态迁移（canTransit(X,X)=false）。
     * 修复轮 I2：!isRetryable 的渲染异常（配置/内容性失败）直接 FAILED，不进循环。
     *
     * <p>与 {@link #qaRetryOrFail} 的分工（Ruling-17）：本方法只处理渲染/审帧链<b>异常</b>
     * （exit≠0/超时等环境性失败，就地重试）；QA <b>判负</b>（审帧出了 FAIL 清单）走
     * qaRetryOrFail 回 GENERATING 重生成，不在本方法重试。</p>
     *
     * <p>T12 F4 复盘：就地分支不发生状态迁移，按设计不落 stageHistory——历史只记
     * canTransit 成功的 ENTER，痕迹是 qaRounds/lastError（Ruling-18 后原 QA→RENDERING→QA
     * 双 ENTER 回归用例改为就地重审断言，见 JobOrchestratorTest F4 回归）。</p>
     */
    private Job renderRetryOrFail(Job job, RenderWorker.RenderException e, Ctx ctx) {
        if (!e.isRetryable()) {
            return failJob(job, "渲染失败（不可重试）：" + e.getMessage(), e, ctx);
        }
        // T15b③ off-by-one（T14a M-2 顺延）：先自增后比较——qaRounds=已消耗轮数，
        // max=N 恰 N 次（旧码先比较后自增，max=N 实跑 N+1）；与 qaRetryOrFail 同款语义。
        // 渲染链不加墙钟（T15b②）：渲染进程已有 30min spawn 超时硬界。
        int round = job.getQaRounds() + 1;
        job.setQaRounds(round);
        if (round < props.getQa().getMaxRounds()) {
            job.setLastError(tail(String.valueOf(e.getMessage()), LAST_ERROR_TAIL));
            saveTolerant(job);
            return job;   // RENDERING 就地重渲 / QA 就地重审（留原状态，循环重跑本阶段）
        }
        return failJob(job, "渲染/审帧链失败预算（" + props.getQa().getMaxRounds() + "）耗尽：" + e.getMessage(), e, ctx);
    }

    /**
     * QA 判负路由（Ruling-17 结构性主修，R2 实证驱动）：QA 判负 ≠ 环境性失败——job1 六轮
     * 驳回同因（结论卡折行类生成内容缺陷），盲重渲 6/6 复现同缺陷，随机重试对几何溢出类
     * 缺陷命中率极低。故预算内判负 → <b>带 FAIL 清单回 GENERATING 重生成</b>（与 V1-V4 驳回
     * 同一机制、同一错误注入通道），素材/剧本/TTS 随之自然重做（Ruling-18：预审本就先于渲染，
     * 驳回循环零渲染成本）；qaRounds 继续计数，第 maxRounds 轮判负 → FAILED。
     *
     * <p>F2-R2 off-by-one 修复：先自增后比较（qaRounds = 已消耗轮数），maxRounds=5 恰好
     * 5 次 QA 判定——旧代码先比较后自增实跑 6 判 6 渲，第 6 判负才 FAILED 且报文仍称 5 轮。</p>
     *
     * <p>分工边界：本方法只服务 QA <b>判负</b>；渲染/审帧链异常（exit≠0/超时等环境性失败）
     * 走 {@link #renderRetryOrFail} 的就地重渲/就地重审路径，保持不变。</p>
     */
    private Job qaRetryOrFail(Job job, QaFrameCheck.QaResult result, Ctx ctx) {
        int round = job.getQaRounds() + 1;
        job.setQaRounds(round);
        String fails = String.join("; ", result.fails());
        if (round < props.getQa().getMaxRounds()) {
            List<String> errors = new ArrayList<>(result.fails());
            if (errors.isEmpty()) {
                // T7 评审 I 项同款兜底：判负却无具体 FAIL 行时补通用理由，避免空清单重试
                errors.add("QA 审帧判负（审帧器未给出具体 FAIL 行）");
            }
            ctx.reviewErrors = List.copyOf(errors);
            // 修复轮 I1 同理：清续跑标记——QA 驳回后 GENERATING 必须真正重生成
            ctx.contentResumed = false;
            // 剧本将重生成 → 旧 TTS 音频/台词 wav/工作区（含旧 content.json 与旧 qa 产物）全部作废，
            // SPEAKING 整段重录、下轮 QA 按新剧本现建工作区
            ctx.audioMeta = null;
            ctx.lineWavs = Map.of();
            ctx.workspace = null;
            job.setLastError(tail(fails, LAST_ERROR_TAIL));
            discardStaleFinalQuietly(job);   // 防御残留旧成片：防 RENDERING 断点判定跳过唯一一次渲染
            return enterGeneratingAndReturn(job,
                    "QA 未过（第 " + round + " 轮），FAIL 清单回传重生成");
        }
        return failJob(job, "QA 审帧 " + props.getQa().getMaxRounds() + " 轮未过：" + fails, null, ctx);
    }

    /**
     * QA 判负路由前丢弃可能的残留成片（golden 2026-08-29 实测 bug）：doRendering 入口以
     * {@code final.mp4 已在盘} 判定「渲染已完成」直接归档收尾，残留旧片不删则唯一一次渲染
     * 不发生、DONE 落在过期成片上。Ruling-18 新链路 QA 先于渲染、本无成片可言，此删除保留
     * 为对升级窗口期（旧版在途任务：QA 判负时 artifacts 已有前一轮成片）的防御。
     */
    private void discardStaleFinalQuietly(Job job) {
        try {
            Files.deleteIfExists(artifactsFinal(job.getId()));
        } catch (IOException e) {
            log.warn("job={} QA 重渲前丢弃旧成片失败（残留文件 {}）：{}", job.getId(),
                    artifactsFinal(job.getId()), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 落库与回调

    /** enterStage + 容错落库；终态（或行消失）返回 null 停止循环。 */
    private Job enterAndSave(Job job, JobStatus to, String note) {
        Job saved = enterAndSaveAndReturn(job, to, note);
        return saved == null || saved.getStatus().isTerminal() ? null : saved;
    }

    /** enterStage + 容错落库，返回库内最新状态（供 DONE/CANCELLED 收尾判断）。 */
    private Job enterAndSaveAndReturn(Job job, JobStatus to, String note) {
        job.enterStage(to, note);
        return saveTolerant(job);
    }

    /**
     * 容错落库（取消 TOCTOU 兜底，T3 评审 M 项）：save 撞 @Version
     * （如 DELETE cancel 与编排器并发落库）→ 重读一次以库内最新状态继续，绝不向上抛。
     */
    private Job saveTolerant(Job job) {
        try {
            return repo.save(job);
        } catch (ObjectOptimisticLockingFailureException raced) {
            log.info("job={} save 撞乐观锁（并发落库，如取消），重读一次以最新状态继续", job.getId());
            return repo.findById(job.getId()).orElse(null);
        }
    }

    private void cleanupWorkspaceQuietly(String jobId) {
        try {
            workspaceManager.cleanup(jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("job={} workspace 清理被中断", jobId);
        } catch (RuntimeException e) {
            log.warn("job={} workspace 清理失败（残留工作区将在下次 create 时整删）：{}", jobId, e.getMessage());
        }
    }

    /** M2：渲染/QA 后取消 → 成片丢弃（不入 artifacts，GET /video 因此 404）。 */
    private void discardArtifactsQuietly(String jobId) {
        try {
            Files.deleteIfExists(artifactsFinal(jobId));
        } catch (IOException e) {
            log.warn("job={} 成片丢弃失败（残留文件 {}）：{}", jobId, artifactsFinal(jobId), e.getMessage());
        }
    }

    /**
     * 挂起终态回调（I3）：只组装载荷不发送——notify（含退避 sleep 最长数十秒）由
     * loop/process 层在全部信号量释放后统一执行，绝不占闸阻塞其他任务。
     */
    private void deferCallback(Job job, JobStatus status, String error, Ctx ctx) {
        if (job.getCallbackUrl() == null || job.getCallbackUrl().isBlank()) {
            return;
        }
        String videoUrl = status == JobStatus.DONE
                ? props.getPublicBaseUrl() + "/api/v1/jobs/" + job.getId() + "/video"
                : null;
        ctx.callbackUrl = job.getCallbackUrl();
        ctx.pendingCallback = new CallbackClient.CallbackPayload(job.getId(), status.name(), videoUrl, error);
    }

    /** 发送挂起回调（仅 loop/process 层调用——此时任何信号量都已释放）。 */
    private void fireCallback(Ctx ctx) {
        if (ctx.pendingCallback == null) {
            return;
        }
        try {
            callbackClient.notify(ctx.callbackUrl, ctx.pendingCallback);
        } catch (RuntimeException e) {
            // CallbackClient 承诺不抛；纯防御，回调失败绝不影响终态
            log.warn("回调异常 url={}", ctx.callbackUrl, e);
        }
        ctx.pendingCallback = null;
        ctx.callbackUrl = null;
    }

    // ------------------------------------------------------------------ 断点续跑

    /** 扫描 workspace/artifacts 断点产物（content → 跳过审题/生成；audio+wav 齐 → 跳过 TTS）。 */
    private Ctx loadResume(Job job) {
        Ctx ctx = new Ctx();
        Path contentJson = wsContentJson(job.getId());
        if (Files.isRegularFile(contentJson)) {
            try {
                ctx.content = ContentJson.readFrom(contentJson);
                ctx.contentResumed = true;
                log.info("job={} 断点续跑：发现 content.json，跳过 EXTRACTING/GENERATING", job.getId());
            } catch (UncheckedIOException e) {
                log.warn("job={} 断点续跑读 content.json 失败，回退全流程：{}", job.getId(), e.getMessage());
            }
        }
        Path audioMetaJson = wsAudioMetaJson(job.getId());
        if (ctx.content != null && Files.isRegularFile(audioMetaJson)) {
            try {
                AudioMeta meta = AudioMeta.readFrom(audioMetaJson);
                Map<Integer, byte[]> wavs = readWorkspaceLineWavs(job.getId(), meta,
                        ctx.content.scenes() == null ? 0 : ctx.content.scenes().size());
                if (wavs != null) {
                    ctx.audioMeta = meta;
                    ctx.lineWavs = wavs;
                    log.info("job={} 断点续跑：audio_meta.json 与 {} 个台词 wav 齐，跳过 SPEAKING", job.getId(), wavs.size());
                }
            } catch (UncheckedIOException e) {
                log.warn("job={} 断点续跑读 audio_meta.json 失败，回退整段 TTS：{}", job.getId(), e.getMessage());
            }
        }
        return ctx;
    }

    /** workspace lines 目录的 wav 全齐才返回 map（断点续跑判定：lines wav 数齐）；否则 null。 */
    private Map<Integer, byte[]> readWorkspaceLineWavs(String jobId, AudioMeta meta, int sceneCount) {
        if (meta.getLines() == null || meta.getLines().isEmpty()
                || sceneCount <= 0 || meta.getLines().size() != sceneCount) {
            return null;
        }
        Map<Integer, byte[]> wavs = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getLines().size(); i++) {
            Path wav = wsLinesDir(jobId).resolve(String.format("line_%02d.wav", i));
            if (!Files.isRegularFile(wav)) {
                return null;
            }
            try {
                wavs.put(i, Files.readAllBytes(wav));
            } catch (IOException e) {
                throw new UncheckedIOException("断点续跑读台词 wav 失败：" + wav, e);
            }
        }
        return wavs;
    }

    private Map<Integer, byte[]> readLineWavs(Path staging, int lineCount) {
        Map<Integer, byte[]> wavs = new LinkedHashMap<>();
        for (int i = 1; i <= lineCount; i++) {
            Path wav = staging.resolve(String.format("line_%02d.wav", i));
            try {
                wavs.put(i, Files.readAllBytes(wav));
            } catch (IOException e) {
                throw new UncheckedIOException("TTS 产物读回失败：" + wav, e);
            }
        }
        return wavs;
    }

    /** 断点续跑的 V2 基准：用 content.json 的 problem 段拼 ExtractResult（同源数据，V2 恒过）。 */
    private static ExtractResult extractedFromProblem(ContentJson content) {
        List<ExtractResult.Line> lines = new ArrayList<>();
        for (ContentJson.Line line : content.problem().lines()) {
            List<ExtractResult.Seg> segs = new ArrayList<>();
            for (ContentJson.Seg seg : line.segments()) {
                segs.add(new ExtractResult.Seg(seg.type(), seg.value()));
            }
            lines.add(new ExtractResult.Line(line.id(), segs));
        }
        String problemType = content.meta() != null ? content.meta().problemType() : null;
        return new ExtractResult(problemType, List.copyOf(lines));
    }

    // ------------------------------------------------------------------ 路径与小工具

    private Path wsContentJson(String jobId) {
        return Path.of(props.getWorkspaceDir()).resolve(jobId).resolve("src/data/content.json");
    }

    private Path wsAudioMetaJson(String jobId) {
        return Path.of(props.getWorkspaceDir()).resolve(jobId).resolve("src/data/audio_meta.json");
    }

    private Path wsLinesDir(String jobId) {
        return Path.of(props.getWorkspaceDir()).resolve(jobId).resolve("public/audio/lines");
    }

    private Path artifactsFinal(String jobId) {
        return Path.of(props.getArtifactsDir()).resolve(jobId).resolve("final.mp4");
    }

    private static String imagePayload(Job job) {
        return job.getImageBase64() == null ? "" : new String(job.getImageBase64(), StandardCharsets.UTF_8);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("TTS 暂存目录清理失败（残留系统临时文件）：{}", dir);
        }
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /** 取尾部长度截断（lastError 存堆栈尾）。 */
    static String tail(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    /** 历史 note 用的短摘要（前 120 字符）。 */
    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String stripped = s.strip();
        return stripped.length() <= 120 ? stripped : stripped.substring(0, 120) + "…";
    }

    /** 单任务处理过程中的内存上下文（断点续跑产物 + 跨阶段传递），不落库。 */
    static final class Ctx {
        ContentJson content;
        /** content 来自盘上断点续跑（true）还是本轮生成（false）——驳回重生成必须区分。 */
        boolean contentResumed;
        ExtractResult extracted;
        boolean extractedFromResume;
        AudioMeta audioMeta;
        Map<Integer, byte[]> lineWavs = Map.of();
        List<String> reviewErrors = List.of();
        Path workspace;
        Path artifactsDir;
        /** 挂起的终态回调（I3）：handler 内只挂起，loop/process 层闸外统一发送。 */
        CallbackClient.CallbackPayload pendingCallback;
        String callbackUrl;
    }
}
