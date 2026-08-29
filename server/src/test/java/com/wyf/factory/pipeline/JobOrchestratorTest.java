package com.wyf.factory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.JobStatus;
import com.wyf.factory.domain.StageHistoryEntry;
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
import com.wyf.factory.validate.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 编排器全链契约（全 mock，零真调，@Scheduled 直调 poll() 不起真调度）：
 * happy path 序列/调用顺序 / 驳回回传重试 / 驳回预算尽 / QA 回退重渲 / 审题判死 /
 * 断点续跑 / 取消检查点 / 领单 CAS 抢占 / 取消 TOCTOU 兜底。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobOrchestratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JOB_ID = "job-1";
    private static final String CALLBACK_URL = "http://cb.example/hook";
    private static final byte[] WAV_BYTES = {1, 2, 3, 4, 5, 6, 7, 8};

    @TempDir
    Path tempDir;

    @Mock JobRepository repo;
    @Mock ExtractStation extractStation;
    @Mock MaterialStation materialStation;
    @Mock ScriptStation scriptStation;
    @Mock V1Structural v1;
    @Mock V2Fidelity v2;
    @Mock V3Refs v3;
    @Mock V4Judge v4;
    @Mock TtsPipeline ttsPipeline;
    @Mock WorkspaceManager workspaceManager;
    @Mock RenderWorker renderWorker;
    @Mock QaFrameCheck qaFrameCheck;
    @Mock CallbackClient callbackClient;

    private AppProperties props;
    private JobOrchestrator orchestrator;

    private static final ExtractResult EXTRACT = new ExtractResult("计算题",
            List.of(new ExtractResult.Line("L1", List.of(new ExtractResult.Seg("text", "设 f(x)=x^3，求 a。")))));
    private static final Material MATERIAL = new Material(List.of(), List.of(), List.of(), List.of());
    private static final String CONTENT_JSON = """
            {"meta":{"aspect":"16:9","problemType":"计算题"},
             "problem":{"lines":[{"id":"L1","segments":[{"type":"text","value":"设 f(x)=x^3，求 a。"}]}]},
             "knowledge":[],"steps":[],"pitfalls":[],"generalMethod":[],
             "scenes":[{"id":"s01","act":2,"component":"problem-card","ttsText":"第一句口播","props":{}}]}
            """;
    private static final String AUDIO_META_JSON = """
            {"voice":"Cherry","model":"qwen-tts","rate":1.0,"fps":30,"breathSec":0.18,"act5TailSec":2.0,
             "fixed":{"act1":{"file":"audio/fixed/act1.wav","durationSec":2.0},
                      "act5":{"file":"audio/fixed/act5.wav","durationSec":1.0}},
             "lines":[{"index":1,"sceneId":"s01","file":"audio/lines/line_01.wav","durationSec":1.234,"text":"第一句口播"}],
             "totalFrames":69}
            """;

    @BeforeEach
    void setUp() throws Exception {
        props = new AppProperties();
        props.setWorkspaceDir(tempDir.resolve("workspace").toString());
        props.setArtifactsDir(tempDir.resolve("artifacts").toString());
        orchestrator = new JobOrchestrator(repo, extractStation, materialStation, scriptStation,
                v1, v2, v3, v4, ttsPipeline, workspaceManager, renderWorker, qaFrameCheck,
                new ResourceSemaphores(props), callbackClient, props);
    }

    // ---- fixtures ----

    private Job queuedJob() {
        Job job = new Job();
        job.setId(JOB_ID);
        job.setInputType("TEXT");
        job.setInputText("设 f(x)=x^3+ax^2+x 在 R 上单调递增，求 a 的取值范围");
        job.setAspect("16:9");
        job.setVoice("Cherry");
        job.setCallbackUrl(CALLBACK_URL);
        return job;
    }

    private Job claimedJob() {
        Job job = queuedJob();
        job.enterStage(JobStatus.EXTRACTING, "领单：开始处理");
        return job;
    }

    /** 沿正向链推进到目标阶段（测试起点），历史含 QUEUED→…→target 全部 ENTER。 */
    private Job claimedJobAt(JobStatus target) {
        Job job = queuedJob();
        for (JobStatus step : List.of(JobStatus.EXTRACTING, JobStatus.GENERATING, JobStatus.REVIEWING,
                JobStatus.SPEAKING, JobStatus.RENDERING)) {
            if (step.ordinal() > target.ordinal()) {
                break;
            }
            job.enterStage(step, "测试推进");
        }
        return job;
    }

    /** 预置 workspace 断点产物：content.json + audio_meta.json + line_01.wav（wav 数与 scenes 齐）。 */
    private void presetResumeArtifacts() throws java.io.IOException {
        Path ws = wsPath();
        Files.createDirectories(ws.resolve("src/data"));
        Files.createDirectories(ws.resolve("public/audio/lines"));
        Files.writeString(ws.resolve("src/data/content.json"), CONTENT_JSON);
        Files.writeString(ws.resolve("src/data/audio_meta.json"), AUDIO_META_JSON);
        Files.write(ws.resolve("public/audio/lines/line_01.wav"), WAV_BYTES);
    }

    private Path wsPath() {
        return Path.of(props.getWorkspaceDir()).resolve(JOB_ID);
    }

    private Path artifactsFinal() {
        return Path.of(props.getArtifactsDir()).resolve(JOB_ID).resolve("final.mp4");
    }

    private void stubRepo(Job job) {
        when(repo.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(repo.save(any())).thenAnswer(returnsFirstArg());
    }

    private void stubStationsOk() throws Exception {
        when(extractStation.extract(anyString())).thenReturn(EXTRACT);
        when(materialStation.generate(any(), anyList())).thenReturn(MATERIAL);
        when(scriptStation.assemble(any(), any(), anyList()))
                .thenReturn(JSON.readValue(CONTENT_JSON, ContentJson.class));
        when(v1.validate(any())).thenReturn(ValidationResult.ok());
        when(v2.validate(any())).thenReturn(ValidationResult.ok());
        when(v3.validate(any())).thenReturn(ValidationResult.ok());
        when(v4.validate(any())).thenReturn(ValidationResult.ok());
        when(ttsPipeline.synthesizeAll(any(), any())).thenAnswer(inv -> {
            Path staging = inv.getArgument(1);
            Files.createDirectories(staging);
            Files.write(staging.resolve("line_01.wav"), WAV_BYTES);
            return JSON.readValue(AUDIO_META_JSON, AudioMeta.class);
        });
        when(workspaceManager.create(eq(JOB_ID), any(), any(), any())).thenReturn(wsPath());
        when(renderWorker.render(any(Path.class))).thenReturn(artifactsFinal());
        when(qaFrameCheck.check(any(Path.class))).thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));
    }

    private List<String> historyStages(Job job) {
        return job.getStageHistory().stream().map(StageHistoryEntry::getStage).toList();
    }

    // ---- 1. happy path ----

    @Test
    @DisplayName("happy path：QUEUED→…→DONE 全序列 + 协作者调用顺序 + DONE 清理与回调")
    void happyPath_fullSequence_inOrder() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING", "GENERATING", "REVIEWING", "SPEAKING",
                "RENDERING", "QA", "DONE");
        // artifactsDir 落库为绝对路径（GET /video 按 artifactsDir 直读 final.mp4）
        assertThat(job.getArtifactsDir())
                .isEqualTo(artifactsFinal().getParent().toAbsolutePath().normalize().toString());

        InOrder flow = inOrder(extractStation, materialStation, scriptStation,
                v1, v2, v3, v4, ttsPipeline, workspaceManager, renderWorker, qaFrameCheck, callbackClient);
        flow.verify(extractStation).extract(job.getInputText());
        flow.verify(materialStation).generate(eq(EXTRACT), anyList());
        flow.verify(scriptStation).assemble(eq(EXTRACT), eq(MATERIAL), anyList());
        flow.verify(v1).validate(any());
        flow.verify(v2).validate(any());
        flow.verify(v3).validate(any());
        flow.verify(v4).validate(any());
        flow.verify(ttsPipeline).synthesizeAll(any(), any());
        flow.verify(workspaceManager).create(eq(JOB_ID), any(), any(), any());
        flow.verify(renderWorker).render(wsPath());
        flow.verify(qaFrameCheck).check(wsPath());
        flow.verify(workspaceManager).cleanup(JOB_ID);
        flow.verify(callbackClient).notify(eq(CALLBACK_URL), any());

        ArgumentCaptor<CallbackClient.CallbackPayload> payload = ArgumentCaptor.forClass(CallbackClient.CallbackPayload.class);
        verify(callbackClient).notify(eq(CALLBACK_URL), payload.capture());
        assertThat(payload.getValue().jobId()).isEqualTo(JOB_ID);
        assertThat(payload.getValue().status()).isEqualTo("DONE");
        assertThat(payload.getValue().videoUrl())
                .isEqualTo("http://localhost:8080/api/v1/jobs/" + JOB_ID + "/video");
        assertThat(payload.getValue().error()).isNull();
    }

    // ---- 2. 校验驳回 2 轮后过 ----

    @Test
    @DisplayName("驳回回传：REVIEWING 驳回 2 轮后过 → GENERATING 被调 3 次，第 2/3 次带错误清单")
    void reviewRejected_twoRounds_thenPass() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(v4.validate(any()))
                .thenReturn(ValidationResult.fail(List.of("V1/x: 差异一")))
                .thenReturn(ValidationResult.fail(List.of("V1/y: 差异二")))
                .thenReturn(ValidationResult.ok());

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getReviewRetries()).isEqualTo(2);

        ArgumentCaptor<List<String>> materialErrors = ArgumentCaptor.forClass(List.class);
        verify(materialStation, times(3)).generate(any(), materialErrors.capture());
        assertThat(materialErrors.getAllValues()).hasSize(3);
        assertThat(materialErrors.getAllValues().get(0)).isEmpty();
        assertThat(materialErrors.getAllValues().get(1)).containsExactly("V1/x: 差异一");
        assertThat(materialErrors.getAllValues().get(2)).containsExactly("V1/y: 差异二");

        ArgumentCaptor<List<String>> scriptErrors = ArgumentCaptor.forClass(List.class);
        verify(scriptStation, times(3)).assemble(any(), any(), scriptErrors.capture());
        assertThat(scriptErrors.getAllValues().get(0)).isEmpty();
        assertThat(scriptErrors.getAllValues().get(1)).containsExactly("V1/x: 差异一");
        assertThat(scriptErrors.getAllValues().get(2)).containsExactly("V1/y: 差异二");

        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING",
                "GENERATING", "REVIEWING", "GENERATING", "REVIEWING", "GENERATING", "REVIEWING",
                "SPEAKING", "RENDERING", "QA", "DONE");
    }

    // ---- 3. 校验预算尽 → FAILED ----

    @Test
    @DisplayName("驳回预算尽：连续驳回超过 contentMax → FAILED + errorMessage")
    void reviewRejected_budgetExhausted_fails() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(v4.validate(any())).thenReturn(ValidationResult.fail(List.of("V1/x: 死循环差异")));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getReviewRetries()).isEqualTo(props.getRetry().getContentMax());
        assertThat(job.getErrorMessage()).contains("V1/x: 死循环差异");
        verify(v4, times(props.getRetry().getContentMax() + 1)).validate(any());
        verify(ttsPipeline, never()).synthesizeAll(any(), any());
    }

    // ---- 4. QA FAIL 回退重渲染 ----

    @Test
    @DisplayName("QA 回退：FAIL 2 轮 → RENDERING 全片重渲 → 第 3 轮过 → DONE")
    void qaFails_twoRounds_rerender_thenPass() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(qaFrameCheck.check(any(Path.class)))
                .thenReturn(new QaFrameCheck.QaResult(false, List.of("FAIL s03 帧 12 画面缺失"), 3))
                .thenReturn(new QaFrameCheck.QaResult(false, List.of("FAIL s05 帧 40 花屏"), 3))
                .thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getQaRounds()).isEqualTo(2);
        verify(renderWorker, times(3)).render(any(Path.class));   // 每轮全片重渲
        verify(qaFrameCheck, times(3)).check(any(Path.class));
        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING", "GENERATING", "REVIEWING", "SPEAKING",
                "RENDERING", "QA", "RENDERING", "QA", "RENDERING", "QA", "DONE");
    }

    // ---- 4b. T12 F4 回归：审帧链异常回退重渲，QA→RENDERING→QA 两条 ENTER 均落 history ----

    @Test
    @DisplayName("F4 回归：QA 审帧链 retryable 异常（EPERM 形态）→ 回退重渲，QA→RENDERING→QA 两条 ENTER 均在 stageHistory")
    void qaChainException_rerender_historyRecordsBothEnters() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(qaFrameCheck.check(any(Path.class)))
                .thenThrow(new RenderWorker.RenderException(
                        "审帧截图失败（s01 帧 0）exit=1：EPERM: operation not permitted, rmdir", true))
                .thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(renderWorker, times(2)).render(any(Path.class));   // 回退后全片重渲一次
        assertThat(job.getQaRounds()).isEqualTo(1);
        // 关键不变量：每次 canTransit 成功都恰落一条 ENTER——两条迁移均不得缺席
        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING", "GENERATING", "REVIEWING", "SPEAKING",
                "RENDERING", "QA", "RENDERING", "QA", "DONE");
        assertThat(job.getStageHistory())
                .extracting(StageHistoryEntry::getNote)
                .anySatisfy(note -> assertThat(note).contains("审帧链异常"));
    }

    // ---- 5. 审题判死 → 直接 FAILED ----

    @Test
    @DisplayName("FatalExtractException：直接 FAILED 不重试，不进后续工位")
    void fatalExtract_failsImmediately() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(extractStation.extract(anyString()))
                .thenThrow(new ExtractStation.FatalExtractException("审题失败：图片中未识别到数学题"));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("未识别到数学题");
        verify(extractStation, times(1)).extract(anyString());
        assertThat(job.getExtractRetries()).isZero();
        verifyNoInteractions(materialStation, scriptStation, ttsPipeline);
    }

    // ---- 6. 断点续跑 ----

    @Test
    @DisplayName("断点续跑：content.json+audio_meta.json 已在盘 → 审题/生成/TTS 零调用，直接渲染")
    void resume_fromWorkspaceArtifacts() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        // 只 stub 断点后续阶段；断点前工位保持零 stub 零调用
        when(v1.validate(any())).thenReturn(ValidationResult.ok());
        when(v2.validate(any())).thenReturn(ValidationResult.ok());
        when(v3.validate(any())).thenReturn(ValidationResult.ok());
        when(v4.validate(any())).thenReturn(ValidationResult.ok());
        when(workspaceManager.create(eq(JOB_ID), any(), any(), any())).thenReturn(wsPath());
        when(renderWorker.render(any(Path.class))).thenReturn(artifactsFinal());
        when(qaFrameCheck.check(any(Path.class))).thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        // 预置断点产物：workspace/{jobId}/src/data/*.json + lines wav（wav 数与 scenes 齐才可跳过 SPEAKING）
        presetResumeArtifacts();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verifyNoInteractions(extractStation, materialStation, scriptStation, ttsPipeline);
        // 断点续跑时 V2 以 content.json problem 段为基准（历史 note 记录）
        assertThat(job.getStageHistory())
                .extracting(StageHistoryEntry::getNote)
                .anySatisfy(note -> assertThat(note).contains("断点续跑").contains("V2"));
        // wav 字节从盘上读回并交给 WorkspaceManager
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Integer, byte[]>> wavs = ArgumentCaptor.forClass(Map.class);
        verify(workspaceManager).create(eq(JOB_ID), any(), any(), wavs.capture());
        assertThat(wavs.getValue()).containsEntry(1, WAV_BYTES);
        verify(renderWorker).render(wsPath());
    }

    // ---- 7. 取消 ----

    @Test
    @DisplayName("取消：GENERATING 完成后置 cancelRequested → SPEAKING 前停下 CANCELLED + 清理 + 回调")
    void cancel_afterGenerating_stopsBeforeReview() throws Exception {
        Job job = claimedJob();
        AtomicInteger reads = new AtomicInteger();
        when(repo.findById(JOB_ID)).thenAnswer(inv -> {
            if (reads.incrementAndGet() == 4) {
                job.setCancelRequested(true);   // 模拟 DELETE 在 GENERATING 之后、REVIEWING 之前落库
            }
            return Optional.of(job);
        });
        when(repo.save(any())).thenAnswer(returnsFirstArg());
        stubStationsOk();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING", "GENERATING", "REVIEWING", "CANCELLED");
        verify(extractStation).extract(anyString());
        verify(materialStation).generate(any(), anyList());
        verify(v1, never()).validate(any());
        verifyNoInteractions(ttsPipeline, renderWorker, qaFrameCheck);
        verify(workspaceManager).cleanup(JOB_ID);   // 清理工作区、不产 artifacts
        ArgumentCaptor<CallbackClient.CallbackPayload> payload = ArgumentCaptor.forClass(CallbackClient.CallbackPayload.class);
        verify(callbackClient).notify(eq(CALLBACK_URL), payload.capture());
        assertThat(payload.getValue().status()).isEqualTo("CANCELLED");
        assertThat(payload.getValue().videoUrl()).isNull();
    }

    // ---- 8. 领单 CAS 抢占 ----

    @Test
    @DisplayName("并发抢占：两个 poll 领同一 QUEUED，一个进 EXTRACTING，另一个撞乐观锁静默跳过")
    void poll_claimRace_loserSkipsSilently() throws Exception {
        Job job = queuedJob();
        Job rival = queuedJob();   // 第二个 poll 的独立加载（模拟各自的事务快照）
        when(repo.findFirstByStatusOrderByCreatedAtAsc(JobStatus.QUEUED))
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.of(rival));
        AtomicInteger claims = new AtomicInteger();
        when(repo.save(any())).thenAnswer(inv -> {
            if (claims.incrementAndGet() == 2) {
                throw new ObjectOptimisticLockingFailureException(Job.class, JOB_ID);
            }
            return inv.getArgument(0);
        });
        List<Runnable> submitted = new ArrayList<>();
        orchestrator.setJobExecutor(submitted::add);

        assertThatCode(() -> {
            orchestrator.poll();   // 赢家：抢占成功并提交
            orchestrator.poll();   // 输家：CAS 失败，静默 return
        }).doesNotThrowAnyException();

        assertThat(submitted).hasSize(1);
        assertThat(job.getStatus()).isEqualTo(JobStatus.EXTRACTING);
        verify(qaFrameCheck, never()).check(any(Path.class));   // 输家没有把任务推进到任何阶段
    }

    // ---- 9. 取消 TOCTOU 兜底 ----

    @Test
    @DisplayName("取消 TOCTOU：CANCELLED 落库撞乐观锁 → 重读一次以最新状态重试，清理与回调恰好一次")
    void cancel_toctou_optimisticLock_recovered() throws Exception {
        Job job = claimedJob();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        AtomicBoolean restored = new AtomicBoolean();
        when(repo.findById(JOB_ID)).thenAnswer(inv -> {
            int call = reads.incrementAndGet();
            if (call == 4) {
                job.setCancelRequested(true);   // GENERATING 完成后取消标记落库
            }
            if (saves.get() >= 3 && restored.compareAndSet(false, true)) {
                // 库内真相：我们的 CANCELLED 写入被并发的 cancel 落库顶掉（版本冲突），重新读到旧状态+取消标记
                job.setStatus(JobStatus.GENERATING);
                job.setCancelRequested(true);
            }
            return Optional.of(job);
        });
        when(repo.save(any())).thenAnswer(inv -> {
            if (saves.incrementAndGet() == 3) {
                throw new ObjectOptimisticLockingFailureException(Job.class, JOB_ID);   // CANCELLED 那次落库被顶
            }
            return inv.getArgument(0);
        });
        stubStationsOk();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(historyStages(job)).filteredOn(s -> s.equals("CANCELLED")).hasSize(2);   // 第一次被顶掉后重试
        verify(workspaceManager, times(1)).cleanup(JOB_ID);
        verify(callbackClient, times(1)).notify(eq(CALLBACK_URL), any());
    }

    // ---- 10. 渲染可重试异常就地重试 ----

    @Test
    @DisplayName("渲染瞬态失败：RenderException(retryable) 在预算内就地重渲后成功")
    void renderTransientFailure_retriedInPlace() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(renderWorker.render(any(Path.class)))
                .thenThrow(new RenderWorker.RenderException("渲染超时（>30 分钟）", true))
                .thenReturn(artifactsFinal());

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(renderWorker, times(2)).render(any(Path.class));
        assertThat(job.getQaRounds()).isEqualTo(1);
    }

    // ---- 11. 修复轮 I2：不可重试渲染异常直接 FAILED ----

    @Test
    @DisplayName("I2：RenderException(!retryable) → 直接 FAILED，不进重试/回退循环")
    void renderNonRetryableFailure_failsImmediately() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(renderWorker.render(any(Path.class)))
                .thenThrow(new RenderWorker.RenderException("渲染失败 exit=1： composition 不存在", false));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("不可重试").contains("composition 不存在");
        verify(renderWorker, times(1)).render(any(Path.class));
        verify(qaFrameCheck, never()).check(any(Path.class));
        assertThat(job.getQaRounds()).isZero();
    }

    // ---- 12. 修复轮 M2：渲染完成后取消（成片丢弃） ----

    @Test
    @DisplayName("M2：渲染期间置 cancelRequested → 渲染完成后停下 CANCELLED，成片丢弃不入 artifacts")
    void cancel_duringRender_discardsArtifacts() throws Exception {
        Job job = claimedJobAt(JobStatus.RENDERING);
        stubRepo(job);
        presetResumeArtifacts();   // 断点产物让流程直接走到 RENDERING
        when(workspaceManager.create(eq(JOB_ID), any(), any(), any())).thenReturn(wsPath());
        when(renderWorker.render(any(Path.class))).thenAnswer(inv -> {
            job.setCancelRequested(true);   // 渲染进行中 DELETE 落库（渲染中不打断）
            Files.createDirectories(artifactsFinal().getParent());
            Files.write(artifactsFinal(), new byte[]{1, 2, 3});   // render 已把成片拷进 artifacts
            return artifactsFinal();
        });

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING", "GENERATING", "REVIEWING", "SPEAKING", "RENDERING", "CANCELLED");
        verify(renderWorker, times(1)).render(any(Path.class));   // 渲染中不打断，完成后检查点
        verifyNoInteractions(qaFrameCheck);
        assertThat(artifactsFinal()).doesNotExist();   // 成片丢弃（不入 artifacts）
        verify(workspaceManager).cleanup(JOB_ID);
        ArgumentCaptor<CallbackClient.CallbackPayload> payload = ArgumentCaptor.forClass(CallbackClient.CallbackPayload.class);
        verify(callbackClient).notify(eq(CALLBACK_URL), payload.capture());
        assertThat(payload.getValue().status()).isEqualTo("CANCELLED");
        assertThat(payload.getValue().videoUrl()).isNull();
    }

    // ---- 13. 修复轮 M2：QA 完成后取消（成片丢弃，不 DONE） ----

    @Test
    @DisplayName("M2：QA 期间置 cancelRequested → QA 完成后停下 CANCELLED（即使审帧通过），成片丢弃")
    void cancel_duringQa_discardsArtifacts() throws Exception {
        Job job = claimedJobAt(JobStatus.RENDERING);
        stubRepo(job);
        presetResumeArtifacts();
        Files.createDirectories(artifactsFinal().getParent());
        Files.write(artifactsFinal(), new byte[]{1, 2, 3});   // 上一轮渲染已产成片（RENDERING 将被跳过）
        when(workspaceManager.workspacePath(JOB_ID)).thenReturn(wsPath());   // QA 的工作区路径（RENDERING 被跳过、ctx.workspace 未建）
        when(qaFrameCheck.check(any(Path.class))).thenAnswer(inv -> {
            job.setCancelRequested(true);
            return new QaFrameCheck.QaResult(true, List.of(), 3);
        });

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        verify(renderWorker, never()).render(any(Path.class));   // final.mp4 已在盘 → 渲染跳过
        verify(qaFrameCheck, times(1)).check(any(Path.class));
        assertThat(artifactsFinal()).doesNotExist();   // QA 完成后取消 → 成片丢弃，不 DONE
        verify(workspaceManager).cleanup(JOB_ID);
        ArgumentCaptor<CallbackClient.CallbackPayload> payload = ArgumentCaptor.forClass(CallbackClient.CallbackPayload.class);
        verify(callbackClient).notify(eq(CALLBACK_URL), payload.capture());
        assertThat(payload.getValue().status()).isEqualTo("CANCELLED");
    }

    // ---- 14. 修复轮 I4：中断保持状态原样，启动 sweep 能再次认领 ----

    @Test
    @DisplayName("I4：InterruptedException（如 executor 关闭）→ 状态原样保留不 FAILED，sweep 重启后再次提交")
    void interruption_keepsStatus_sweepReclaims() throws Exception {
        Job job = claimedJobAt(JobStatus.RENDERING);
        stubRepo(job);
        presetResumeArtifacts();
        when(workspaceManager.create(eq(JOB_ID), any(), any(), any()))
                .thenThrow(new InterruptedException("executor 已关闭"));

        assertThatCode(() -> orchestrator.process(JOB_ID)).doesNotThrowAnyException();

        assertThat(job.getStatus()).isEqualTo(JobStatus.RENDERING);   // 状态原样，等断点续跑
        assertThat(job.getErrorMessage()).isNull();
        verify(callbackClient, never()).notify(anyString(), any());

        // 启动 sweep：重启后同一任务再次被提交
        List<Runnable> submitted = new ArrayList<>();
        orchestrator.setJobExecutor(submitted::add);
        when(repo.findByStatusNotIn(any())).thenReturn(List.of(job));

        orchestrator.resumeInterrupted();

        assertThat(submitted).hasSize(1);
    }

    // ---- 15. 修复轮 M1：启动 sweep 提交全部非终态（QUEUED 留给 poll） ----

    @Test
    @DisplayName("M1：启动 sweep 逐个提交非终态任务；QUEUED 留给 poll 领单，终态不提交")
    void startupSweep_submitsNonTerminal_skipsQueuedAndTerminal() {
        List<Runnable> submitted = new ArrayList<>();
        orchestrator.setJobExecutor(submitted::add);
        Job extracting = claimedJob();
        Job rendering = claimedJobAt(JobStatus.RENDERING);
        Job queued = queuedJob();
        Job done = queuedJob();
        done.setStatus(JobStatus.DONE);
        when(repo.findByStatusNotIn(any())).thenReturn(List.of(extracting, queued, rendering, done));

        orchestrator.resumeInterrupted();

        assertThat(submitted).hasSize(2);   // EXTRACTING + RENDERING
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<JobStatus>> excluded = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repo).findByStatusNotIn(excluded.capture());
        assertThat(excluded.getValue())
                .containsExactlyInAnyOrder(JobStatus.DONE, JobStatus.FAILED, JobStatus.CANCELLED);
    }

    // ---- 16. 修复轮 I1：续跑后驳回必须真正重生成 ----

    @Test
    @DisplayName("I1：断点续跑的剧本被驳回 → 清续跑标记，GENATING 真正重生成（而非复用旧 content）")
    void resume_thenRejected_regeneratesContent() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        presetResumeArtifacts();
        when(v1.validate(any())).thenReturn(ValidationResult.ok());
        when(v2.validate(any())).thenReturn(ValidationResult.ok());
        when(v3.validate(any())).thenReturn(ValidationResult.ok());
        when(v4.validate(any()))
                .thenReturn(ValidationResult.fail(List.of("V1/x: 续跑剧本不合格")))
                .thenReturn(ValidationResult.ok());
        when(materialStation.generate(any(), anyList())).thenReturn(MATERIAL);
        when(scriptStation.assemble(any(), any(), anyList()))
                .thenReturn(JSON.readValue(CONTENT_JSON, ContentJson.class));
        when(workspaceManager.create(eq(JOB_ID), any(), any(), any())).thenReturn(wsPath());
        when(renderWorker.render(any(Path.class))).thenReturn(artifactsFinal());
        when(qaFrameCheck.check(any(Path.class))).thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(extractStation, never()).extract(anyString());   // 题干仍走续跑
        // 续跑首次跳过生成；驳回后必须真正重生成一次（I1 修复前 contentResumed 恒 true 会零调用）
        verify(materialStation, times(1)).generate(any(), anyList());
        verify(scriptStation, times(1)).assemble(any(), any(), anyList());
        verify(v4, times(2)).validate(any());
        assertThat(job.getReviewRetries()).isEqualTo(1);
    }
}
