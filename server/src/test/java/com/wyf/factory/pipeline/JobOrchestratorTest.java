package com.wyf.factory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.JobReviewError;
import com.wyf.factory.domain.JobStatus;
import com.wyf.factory.domain.StageHistoryEntry;
import com.wyf.factory.glm.GlmException;
import com.wyf.factory.render.ProcessRunner;
import com.wyf.factory.render.QaFrameCheck;
import com.wyf.factory.render.RenderWorker;
import com.wyf.factory.render.WorkspaceManager;
import com.wyf.factory.repo.JobRepository;
import com.wyf.factory.repo.JobReviewErrorRepository;
import com.wyf.factory.stations.ExtractResult;
import com.wyf.factory.stations.ExtractStation;
import com.wyf.factory.stations.ShardGenException;
import com.wyf.factory.tts.AudioMeta;
import com.wyf.factory.tts.TtsPipeline;
import com.wyf.factory.validate.V1Structural;
import com.wyf.factory.validate.V2Fidelity;
import com.wyf.factory.validate.V3Refs;
import com.wyf.factory.validate.V4Judge;
import com.wyf.factory.validate.ValidationResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 编排器全链契约（全 mock，零真调，@Scheduled 直调 poll() 不起真调度）：
 * happy path 序列（Ruling-18：SPEAKING→QA→RENDERING→DONE，渲染只走一次）/调用顺序 /
 * 驳回回传重试 / 驳回预算尽 / QA 判负回传重生成（Ruling-17）与 QA 预算边界（F2-R2，T14a 恰 5 判）/
 * 审题判死 / 断点续跑（含 QA 工作区与成片复用）/ 取消检查点 / 领单 CAS 抢占 / 取消 TOCTOU 兜底。
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
    @Mock JobReviewErrorRepository reviewErrorRepo;
    @Mock ExtractStation extractStation;
    @Mock GenShardPipeline genPipeline;
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
        orchestrator = new JobOrchestrator(repo, reviewErrorRepo, extractStation, genPipeline,
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

    /** 沿正向链（Ruling-18：SPEAKING→QA→RENDERING）推进到目标阶段（测试起点），历史含全部 ENTER。 */
    private Job claimedJobAt(JobStatus target) {
        Job job = queuedJob();
        for (JobStatus step : List.of(JobStatus.EXTRACTING, JobStatus.GENERATING, JobStatus.REVIEWING,
                JobStatus.SPEAKING, JobStatus.QA, JobStatus.RENDERING)) {
            job.enterStage(step, "测试推进");
            if (step == target) {
                break;
            }
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
        when(genPipeline.generate(any(), anyList(), any()))
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
        when(renderWorker.render(any(Path.class), any())).thenReturn(artifactsFinal());
        when(qaFrameCheck.check(any(Path.class))).thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));
    }

    private List<String> historyStages(Job job) {
        return job.getStageHistory().stream().map(StageHistoryEntry::getStage).toList();
    }

    // ---- 1. happy path ----

    @Test
    @DisplayName("happy path（Ruling-18）：QUEUED→…→SPEAKING→QA→RENDERING→DONE 全序列 + 协作者调用顺序 + DONE 清理与回调")
    void happyPath_fullSequence_inOrder() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING", "GENERATING", "REVIEWING", "SPEAKING",
                "QA", "RENDERING", "DONE");
        // artifactsDir 落库为绝对路径（GET /video 按 artifactsDir 直读 final.mp4）
        assertThat(job.getArtifactsDir())
                .isEqualTo(artifactsFinal().getParent().toAbsolutePath().normalize().toString());

        InOrder flow = inOrder(extractStation, genPipeline,
                v1, v2, v3, v4, ttsPipeline, workspaceManager, renderWorker, qaFrameCheck, callbackClient);
        flow.verify(extractStation).extract(job.getInputText());
        flow.verify(genPipeline).generate(eq(EXTRACT), anyList(), any());
        flow.verify(v1).validate(any());
        flow.verify(v2).validate(any());
        flow.verify(v3).validate(any());
        flow.verify(v4).validate(any());
        flow.verify(ttsPipeline).synthesizeAll(any(), any());
        // QA 前置：审帧工作区由 QA 现建（渲染未发生），审帧通过后才渲染，渲染成功即 DONE（无第二轮 QA）
        flow.verify(workspaceManager).create(eq(JOB_ID), any(), any(), any());
        flow.verify(qaFrameCheck).check(wsPath());
        flow.verify(renderWorker).render(wsPath(), "1080p");   // T17：缺省任务渲染 1080p（落库 resolution 透传）
        flow.verify(workspaceManager).cleanup(JOB_ID);
        flow.verify(callbackClient).notify(eq(CALLBACK_URL), any());
        // 渲染复用 QA 预审建好的工作区：create 全链恰一次（不重建 → qa 产物不被洗掉）
        verify(workspaceManager, times(1)).create(eq(JOB_ID), any(), any(), any());

        ArgumentCaptor<CallbackClient.CallbackPayload> payload = ArgumentCaptor.forClass(CallbackClient.CallbackPayload.class);
        verify(callbackClient).notify(eq(CALLBACK_URL), payload.capture());
        assertThat(payload.getValue().jobId()).isEqualTo(JOB_ID);
        assertThat(payload.getValue().status()).isEqualTo("DONE");
        assertThat(payload.getValue().videoUrl())
                .isEqualTo("http://localhost:8080/api/v1/jobs/" + JOB_ID + "/video");
        assertThat(payload.getValue().error()).isNull();
    }

    // ---- 1b. 720p 任务（T17）：落库 resolution 透传 RenderWorker ----

    @Test
    @DisplayName("720p 任务全链：渲染收到 resolution=720p（--scale 映射的编排侧证据），其余链路不变")
    void resolution720p_passedThroughToRenderWorker() throws Exception {
        Job job = claimedJob();
        job.setResolution("720p");
        stubRepo(job);
        stubStationsOk();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(renderWorker).render(wsPath(), "720p");
        verify(renderWorker, never()).render(wsPath(), "1080p");
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

        // 分片生成被调 3 次，第 2/3 次带上一轮驳回错误清单（T18 错误注入通道不变）
        ArgumentCaptor<List<String>> generateErrors = ArgumentCaptor.forClass(List.class);
        verify(genPipeline, times(3)).generate(any(), generateErrors.capture(), any());
        assertThat(generateErrors.getAllValues()).hasSize(3);
        assertThat(generateErrors.getAllValues().get(0)).isEmpty();
        assertThat(generateErrors.getAllValues().get(1)).containsExactly("V1/x: 差异一");
        assertThat(generateErrors.getAllValues().get(2)).containsExactly("V1/y: 差异二");

        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING",
                "GENERATING", "REVIEWING", "GENERATING", "REVIEWING", "GENERATING", "REVIEWING",
                "SPEAKING", "QA", "RENDERING", "DONE");
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

    // ---- 4. QA 判负 → 带 FAIL 清单回 GENERATING 重生成（Ruling-17 结构性主修） ----

    @Test
    @DisplayName("Ruling-17/18：QA 判负 2 轮 → GENERATING 重生成且第 2/3 次生成参数携带 QA FAIL 清单 → 第 3 轮过 → 渲染一次 → DONE")
    void qaFails_twoRounds_regeneratesWithFailList_thenPass() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(qaFrameCheck.check(any(Path.class)))
                .thenReturn(new QaFrameCheck.QaResult(false, List.of("FAIL s03 帧 12 结论卡公式等号后折行"), 3))
                .thenReturn(new QaFrameCheck.QaResult(false, List.of("FAIL s05 帧 40 卡片出缘"), 3))
                .thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getQaRounds()).isEqualTo(2);
        verify(qaFrameCheck, times(3)).check(any(Path.class));
        verify(renderWorker, times(1)).render(any(Path.class), any());      // Ruling-18：预审驳回循环零渲染，通过后只渲一次
        verify(ttsPipeline, times(3)).synthesizeAll(any(), any());   // 剧本变了 → TTS 重做，不得复用旧音频
        verify(workspaceManager, times(3)).create(anyString(), any(), any(), any());   // 每轮预审按当轮剧本现建工作区

        // 第 2/3 次分片生成的错误清单 = 上轮 QA FAIL 清单（与 V 驳回同一错误注入通道）
        ArgumentCaptor<List<String>> generateErrors = ArgumentCaptor.forClass(List.class);
        verify(genPipeline, times(3)).generate(any(), generateErrors.capture(), any());
        assertThat(generateErrors.getAllValues()).hasSize(3);
        assertThat(generateErrors.getAllValues().get(0)).isEmpty();
        assertThat(generateErrors.getAllValues().get(1)).containsExactly("FAIL s03 帧 12 结论卡公式等号后折行");
        assertThat(generateErrors.getAllValues().get(2)).containsExactly("FAIL s05 帧 40 卡片出缘");

        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING",
                "GENERATING", "REVIEWING", "SPEAKING", "QA",
                "GENERATING", "REVIEWING", "SPEAKING", "QA",
                "GENERATING", "REVIEWING", "SPEAKING", "QA",
                "RENDERING", "DONE");
    }

    @Test
    @DisplayName("F2-R2 边界（Ruling-18）：QA 判负恰 maxRounds=5 轮 → FAILED——第 5 判负即终局，全程零渲染")
    void qaFails_maxRoundsExhausted_failsAfterExactlyFiveJudgments() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(qaFrameCheck.check(any(Path.class)))
                .thenReturn(new QaFrameCheck.QaResult(false, List.of("FAIL s03 结论卡多处不当折行"), 3));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getQaRounds()).isEqualTo(props.getQa().getMaxRounds());
        verify(qaFrameCheck, times(5)).check(any(Path.class));    // 恰 5 次 QA 判定（off-by-one 修复，T14a 语义保留）
        verify(genPipeline, times(5)).generate(any(), anyList(), any());
        verify(renderWorker, never()).render(any(Path.class), any());    // Ruling-18：判负循环在渲染之前，一次渲染都不发生
        assertThat(job.getErrorMessage()).contains("QA 审帧 5 轮未过").contains("FAIL s03 结论卡多处不当折行");
        ArgumentCaptor<CallbackClient.CallbackPayload> payload = ArgumentCaptor.forClass(CallbackClient.CallbackPayload.class);
        verify(callbackClient).notify(eq(CALLBACK_URL), payload.capture());
        assertThat(payload.getValue().status()).isEqualTo("FAILED");
        assertThat(payload.getValue().error()).contains("QA 审帧 5 轮未过");
    }

    @Test
    @DisplayName("重生成实效：QA 判负后第二版剧本真正进入渲染与 TTS（workspace 收到新 content）")
    void qaFails_regeneration_producesNewContent() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        ContentJson v1 = JSON.readValue(CONTENT_JSON, ContentJson.class);
        ContentJson v2 = JSON.readValue(CONTENT_JSON.replace("第一句口播", "第二版口播"), ContentJson.class);
        assertThat(v2).isNotEqualTo(v1);
        when(genPipeline.generate(any(), anyList(), any())).thenReturn(v1, v2);
        when(qaFrameCheck.check(any(Path.class)))
                .thenReturn(new QaFrameCheck.QaResult(false, List.of("FAIL s03 结论卡折行"), 3))
                .thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        ArgumentCaptor<ContentJson> rendered = ArgumentCaptor.forClass(ContentJson.class);
        verify(workspaceManager, times(2)).create(eq(JOB_ID), rendered.capture(), any(), any());
        assertThat(rendered.getAllValues().get(0)).isEqualTo(v1);   // 首轮预审工作区写入旧剧本
        assertThat(rendered.getAllValues().get(1)).isEqualTo(v2);   // 驳回后预审与渲染用的都是重生成的新剧本
        ArgumentCaptor<ContentJson> spoken = ArgumentCaptor.forClass(ContentJson.class);
        verify(ttsPipeline, times(2)).synthesizeAll(spoken.capture(), any());
        assertThat(spoken.getAllValues().get(1)).isEqualTo(v2);     // TTS 朗读的也是新剧本
        verify(renderWorker, times(1)).render(any(Path.class), any());     // 只有通过的版本才付渲染成本
    }

    // ---- 4b. T12 F4 回归（Ruling-18 改版）：审帧链异常就地重审，绝不回退重渲让未审成片直达 DONE ----

    @Test
    @DisplayName("F4 回归（Ruling-18）：QA 审帧链 retryable 异常（EPERM 形态）→ 就地重审（无状态迁移），通过后只渲一次 → DONE")
    void qaChainException_inPlaceReaudit_thenRendersOnce() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(qaFrameCheck.check(any(Path.class)))
                .thenThrow(new RenderWorker.RenderException(
                        "审帧截图失败（s01 帧 0）exit=1：EPERM: operation not permitted, rmdir", true))
                .thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(renderWorker, times(1)).render(any(Path.class), any());   // 异常发生在渲染之前，重审不重渲
        verify(workspaceManager, times(1)).create(anyString(), any(), any(), any());   // 重审复用同一工作区
        assertThat(job.getQaRounds()).isEqualTo(1);
        assertThat(job.getLastError()).contains("审帧截图失败");
        // 就地重审不发生状态迁移（canTransit(QA,QA)=false），历史只记一次 QA ENTER——痕迹是 qaRounds/lastError
        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING", "GENERATING", "REVIEWING", "SPEAKING",
                "QA", "RENDERING", "DONE");
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
        verifyNoInteractions(genPipeline, ttsPipeline);
    }

    // ---- 6. 断点续跑 ----

    @Test
    @DisplayName("断点续跑：content.json+audio_meta.json 已在盘 → 审题/生成/TTS 零调用，QA 复用盘上工作区（qa 产物随之保留）后渲染")
    void resume_fromWorkspaceArtifacts() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        // 只 stub 断点后续阶段；断点前工位保持零 stub 零调用
        when(v1.validate(any())).thenReturn(ValidationResult.ok());
        when(v2.validate(any())).thenReturn(ValidationResult.ok());
        when(v3.validate(any())).thenReturn(ValidationResult.ok());
        when(v4.validate(any())).thenReturn(ValidationResult.ok());
        when(workspaceManager.workspacePath(JOB_ID)).thenReturn(wsPath());   // QA 复用盘上工作区的定位入口
        when(renderWorker.render(any(Path.class), any())).thenReturn(artifactsFinal());
        when(qaFrameCheck.check(any(Path.class))).thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        // 预置断点产物：workspace/{jobId}/src/data/*.json + lines wav（wav 数与 scenes 齐才可跳过 SPEAKING）
        presetResumeArtifacts();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verifyNoInteractions(extractStation, genPipeline, ttsPipeline);
        // 断点续跑时 V2 以 content.json problem 段为基准（历史 note 记录）
        assertThat(job.getStageHistory())
                .extracting(StageHistoryEntry::getNote)
                .anySatisfy(note -> assertThat(note).contains("断点续跑").contains("V2"));
        // QA 前置（Ruling-18）：盘上工作区即本轮 content/audio 的出处 → 原地复用（qa 产物不被重建洗掉），
        // 渲染复用同一工作区——create 全程零调用
        verify(workspaceManager, never()).create(anyString(), any(), any(), any());
        verify(qaFrameCheck).check(wsPath());
        verify(renderWorker).render(wsPath(), "1080p");
    }

    @Test
    @DisplayName("Ruling-18 续跑：RENDERING 中断重启且 final.mp4 已在盘 → 跳过渲染与 QA 直接 DONE 归档（渲染后无 QA 轮）")
    void resume_renderedFinalOnDisk_finishesDoneWithoutRerenderOrQa() throws Exception {
        Job job = claimedJobAt(JobStatus.RENDERING);
        stubRepo(job);
        presetResumeArtifacts();   // 断点产物供 loadResume 恢复 content/audio
        Files.createDirectories(artifactsFinal().getParent());
        Files.write(artifactsFinal(), new byte[]{1, 2, 3});   // 渲染已完成但 DONE 未落库的窗口期中断

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(renderWorker, never()).render(any(Path.class), any());
        verify(qaFrameCheck, never()).check(any(Path.class));   // 不再重审——渲染后无 QA 轮
        verify(workspaceManager, never()).create(anyString(), any(), any(), any());
        assertThat(job.getArtifactsDir())
                .isEqualTo(artifactsFinal().getParent().toAbsolutePath().normalize().toString());
        verify(workspaceManager).cleanup(JOB_ID);
        verify(callbackClient).notify(eq(CALLBACK_URL), any());
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
        verify(genPipeline).generate(any(), anyList(), any());
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
        when(renderWorker.render(any(Path.class), any()))
                .thenThrow(new RenderWorker.RenderException("渲染超时（>30 分钟）", true))
                .thenReturn(artifactsFinal());

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(renderWorker, times(2)).render(any(Path.class), any());
        assertThat(job.getQaRounds()).isEqualTo(1);
    }

    // ---- 11. 修复轮 I2：不可重试渲染异常直接 FAILED ----

    @Test
    @DisplayName("I2：RenderException(!retryable) → 直接 FAILED，不进重试循环（预审已在渲染前通过）")
    void renderNonRetryableFailure_failsImmediately() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(renderWorker.render(any(Path.class), any()))
                .thenThrow(new RenderWorker.RenderException("渲染失败 exit=1： composition 不存在", false));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("不可重试").contains("composition 不存在");
        verify(renderWorker, times(1)).render(any(Path.class), any());
        verify(qaFrameCheck, times(1)).check(any(Path.class));   // Ruling-18：预审先于渲染完成
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
        when(renderWorker.render(any(Path.class), any())).thenAnswer(inv -> {
            job.setCancelRequested(true);   // 渲染进行中 DELETE 落库（渲染中不打断）
            Files.createDirectories(artifactsFinal().getParent());
            Files.write(artifactsFinal(), new byte[]{1, 2, 3});   // render 已把成片拷进 artifacts
            return artifactsFinal();
        });

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(historyStages(job)).containsExactly(
                "QUEUED", "EXTRACTING", "GENERATING", "REVIEWING", "SPEAKING", "QA", "RENDERING", "CANCELLED");
        verify(renderWorker, times(1)).render(any(Path.class), any());   // 渲染中不打断，完成后检查点
        verifyNoInteractions(qaFrameCheck);
        assertThat(artifactsFinal()).doesNotExist();   // 成片丢弃（不入 artifacts）
        verify(workspaceManager).cleanup(JOB_ID);
        ArgumentCaptor<CallbackClient.CallbackPayload> payload = ArgumentCaptor.forClass(CallbackClient.CallbackPayload.class);
        verify(callbackClient).notify(eq(CALLBACK_URL), payload.capture());
        assertThat(payload.getValue().status()).isEqualTo("CANCELLED");
        assertThat(payload.getValue().videoUrl()).isNull();
    }

    // ---- 13. 修复轮 M2（Ruling-18 语义）：QA 预审完成后取消（不 DONE、渲染不再发生） ----

    @Test
    @DisplayName("M2：QA 预审期间置 cancelRequested → 预审完成后停下 CANCELLED（即使审帧通过），渲染不再发生")
    void cancel_duringQa_discardsArtifacts() throws Exception {
        Job job = claimedJobAt(JobStatus.QA);
        stubRepo(job);
        presetResumeArtifacts();   // QA 断点续跑：盘上工作区被原地复用
        when(workspaceManager.workspacePath(JOB_ID)).thenReturn(wsPath());
        when(qaFrameCheck.check(any(Path.class))).thenAnswer(inv -> {
            job.setCancelRequested(true);
            return new QaFrameCheck.QaResult(true, List.of(), 3);
        });

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        verify(renderWorker, never()).render(any(Path.class), any());   // 预审先于渲染：取消后渲染成本一次都不付
        verify(qaFrameCheck, times(1)).check(any(Path.class));
        assertThat(artifactsFinal()).doesNotExist();   // 预审阶段无成片可丢（渲染未发生），不入 artifacts
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
        when(genPipeline.generate(any(), anyList(), any()))
                .thenReturn(JSON.readValue(CONTENT_JSON, ContentJson.class));
        when(workspaceManager.create(eq(JOB_ID), any(), any(), any())).thenReturn(wsPath());
        when(renderWorker.render(any(Path.class), any())).thenReturn(artifactsFinal());
        when(qaFrameCheck.check(any(Path.class))).thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(extractStation, never()).extract(anyString());   // 题干仍走续跑
        // 续跑首次跳过生成；驳回后必须真正重生成一次（I1 修复前 contentResumed 恒 true 会零调用）
        verify(genPipeline, times(1)).generate(any(), anyList(), any());
        verify(v4, times(2)).validate(any());
        assertThat(job.getReviewRetries()).isEqualTo(1);
    }

    // ---- 17. T15b②：GENERATING 重试墙钟死线 ----

    @Test
    @DisplayName("T15b②：墙钟超限 → 无视剩余次数直接 FAILED，lastError/errorMessage 注明「重试墙钟超限」，不再重试")
    void generating_wallClockExceeded_failsImmediatelyIgnoringBudget() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(genPipeline.generate(any(), anyList(), any())).thenAnswer(inv -> {
            // 模拟库内死线已过（如重启续跑后墙钟到点）：进 GENERATING 后死线为过去时刻
            job.setGenDeadlineAt(LocalDateTime.now().minusMinutes(1));
            throw new ShardGenException("P2", new GlmException("GLM 请求 IO/超时失败（视为瞬态）", true));
        });

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getGenRetries()).isZero();   // 无视剩余次数：不进入计数路径
        assertThat(job.getErrorMessage()).contains("重试墙钟超限").contains("GLM 请求 IO/超时失败");
        assertThat(job.getLastError()).contains("重试墙钟超限");
        verify(genPipeline, times(1)).generate(any(), anyList(), any());
    }

    @Test
    @DisplayName("T15b②：墙钟未超限 → 既有计数逻辑不变（预算内就地重试至耗尽才 FAILED），且进 GENERATING 落库了死线")
    void generating_withinWallClock_countPathUnchanged_andDeadlinePersisted() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(genPipeline.generate(any(), anyList(), any()))
                .thenThrow(new ShardGenException("P2", new GlmException("GLM 请求 IO/超时失败（视为瞬态）", true)));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("重试 3 次预算耗尽").doesNotContain("墙钟");
        assertThat(job.getGenRetries()).isEqualTo(props.getRetry().getContentMax());
        verify(genPipeline, times(props.getRetry().getContentMax() + 1)).generate(any(), anyList(), any());
        // 死线落库：进 GENERATING 时即落 now+配置值（默认 30min）
        assertThat(job.getGenDeadlineAt()).isNotNull();
        assertThat(job.getGenDeadlineAt()).isAfter(LocalDateTime.now().plusMinutes(28));
        assertThat(job.getGenDeadlineAt()).isBefore(LocalDateTime.now().plusMinutes(31));
    }

    @Test
    @DisplayName("T15b②：合法驳回回环（V 驳回→重进 GENERATING）刷新墙钟死线（每轮 GENERATING 自得全额）")
    void reviewRejection_reentersGenerating_refreshesDeadline() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        LocalDateTime planted = LocalDateTime.now().minusHours(1);   // 模拟上一轮残留的旧死线
        when(v4.validate(any()))
                .thenAnswer(inv -> {
                    job.setGenDeadlineAt(planted);
                    return ValidationResult.fail(List.of("V1/x: 驳回一次"));
                })
                .thenReturn(ValidationResult.ok());

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(historyStages(job)).containsSubsequence(
                "GENERATING", "REVIEWING", "GENERATING", "REVIEWING");
        // 重进 GENERATING 时死线被刷新为新一轮 now+30min，而非残留旧值
        assertThat(job.getGenDeadlineAt()).isAfter(LocalDateTime.now().plusMinutes(29));
        assertThat(job.getReviewRetries()).isEqualTo(1);
    }

    @Test
    @DisplayName("T15b②：渲染链不加墙钟——genDeadlineAt 已过也照常就地重渲（渲染已有 30min spawn 硬界）")
    void renderChain_ignoresGenDeadline() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(renderWorker.render(any(Path.class), any()))
                .thenAnswer(inv -> {
                    job.setGenDeadlineAt(LocalDateTime.now().minusMinutes(1));   // 库内残留的过期死线
                    throw new RenderWorker.RenderException("渲染超时（>30 分钟）", true);
                })
                .thenReturn(artifactsFinal());

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(renderWorker, times(2)).render(any(Path.class), any());   // 死线过期不拦渲染链重试
    }

    // ---- 18. T15b③：renderRetryOrFail off-by-one（T14a M-2） ----

    @Test
    @DisplayName("T15b③ off-by-one：渲染链恰 maxRounds=5 次失败即 FAILED（旧码先比较后自增实跑 N+1=6 次）")
    void renderChain_failsAfterExactlyMaxRoundsAttempts() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(renderWorker.render(any(Path.class), any()))
                .thenThrow(new RenderWorker.RenderException("渲染进程超时/崩溃", true));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        verify(renderWorker, times(props.getQa().getMaxRounds())).render(any(Path.class), any());   // 恰 5 次，非 6
        assertThat(job.getQaRounds()).isEqualTo(props.getQa().getMaxRounds());
        assertThat(job.getErrorMessage()).contains("预算（5）耗尽");
    }

    @Test
    @DisplayName("T15b③ 边界：第 maxRounds 次尝试成功 → DONE（恰 N 次而非 N-1，预算不缩水）")
    void renderChain_succeedsOnLastAllowedAttempt() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        RenderWorker.RenderException boom = new RenderWorker.RenderException("渲染进程超时/崩溃", true);
        when(renderWorker.render(any(Path.class), any()))
                .thenThrow(boom, boom, boom, boom)
                .thenReturn(artifactsFinal());

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(renderWorker, times(5)).render(any(Path.class), any());
        assertThat(job.getQaRounds()).isEqualTo(props.getQa().getMaxRounds() - 1);
    }

    // ---- 19. T19a：reviewErrors 落库（观测回溯 + 重启读回的数据基础） ----

    @Test
    @DisplayName("T19a：V 驳回清单落库——来源 REVIEW、轮次=驳回轮次（1-based）、一行一条原因（含软预警）")
    void reviewRejection_persistsErrorListPerRound() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(v4.validate(any()))
                .thenReturn(ValidationResult.fail(List.of("V1/x: 差异一")))
                .thenReturn(ValidationResult.fail(List.of("V1/y: 差异二", "V3/z: 软预警")))
                .thenReturn(ValidationResult.ok());

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JobReviewError>> saved = ArgumentCaptor.forClass(List.class);
        verify(reviewErrorRepo, times(2)).saveAll(saved.capture());
        List<JobReviewError> round1 = saved.getAllValues().get(0);
        assertThat(round1).extracting(JobReviewError::getJobId, JobReviewError::getSource, JobReviewError::getRound)
                .containsExactly(tuple(JOB_ID, "REVIEW", 1));
        assertThat(round1).extracting(JobReviewError::getReason).containsExactly("V1/x: 差异一");
        List<JobReviewError> round2 = saved.getAllValues().get(1);
        assertThat(round2).extracting(JobReviewError::getReason).containsExactly("V1/y: 差异二", "V3/z: 软预警");
        assertThat(round2).extracting(JobReviewError::getRound).containsOnly(2);
        assertThat(round2).allSatisfy(r -> assertThat(r.getCreatedAt()).isNotNull());
    }

    @Test
    @DisplayName("T19a：QA 判负 FAIL 清单落库——来源 QA、轮次=判负轮次，回传什么落什么")
    void qaRejection_persistsFailListPerRound() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(qaFrameCheck.check(any(Path.class)))
                .thenReturn(new QaFrameCheck.QaResult(false, List.of("FAIL s03 折行"), 3))
                .thenReturn(new QaFrameCheck.QaResult(false, List.of("FAIL s05 出缘"), 3))
                .thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JobReviewError>> saved = ArgumentCaptor.forClass(List.class);
        verify(reviewErrorRepo, times(2)).saveAll(saved.capture());
        assertThat(saved.getAllValues().get(0))
                .extracting(JobReviewError::getJobId, JobReviewError::getSource, JobReviewError::getRound,
                        JobReviewError::getReason)
                .containsExactly(tuple(JOB_ID, "QA", 1, "FAIL s03 折行"));
        assertThat(saved.getAllValues().get(1))
                .extracting(JobReviewError::getReason).containsExactly("FAIL s05 出缘");
        assertThat(saved.getAllValues().get(1)).extracting(JobReviewError::getRound).containsOnly(2);
    }

    @Test
    @DisplayName("T18/T19a：分片轻校验失败清单落库——source=分片名 P2（T18 分片路由观测口径）；瞬态异常无清单不落库")
    void shardValidation_persistsProblems_transientNotPersisted() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(genPipeline.generate(any(), anyList(), any()))
                .thenThrow(new ShardGenException("P2",
                        List.of("素材 steps 条数 2 与骨架计划 3 不一致（条数以骨架为准）",
                                "素材 steps[1].usesAnchor='L9' 与骨架指派 'L2' 不一致（锚点只能领用骨架指派，不得自行改锚）"),
                        null))
                .thenThrow(new ShardGenException("P2", new GlmException("GLM 请求 IO/超时失败（视为瞬态）", true)))
                .thenReturn(JSON.readValue(CONTENT_JSON, ContentJson.class));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JobReviewError>> saved = ArgumentCaptor.forClass(List.class);
        verify(reviewErrorRepo, times(1)).saveAll(saved.capture());   // 瞬态那次不落
        assertThat(saved.getAllValues().get(0))
                .extracting(JobReviewError::getJobId, JobReviewError::getSource, JobReviewError::getRound)
                .containsOnly(tuple(JOB_ID, "P2", 1));   // 一行一条原因：2 行同属本事件；source=分片名
        assertThat(saved.getAllValues().get(0))
                .extracting(JobReviewError::getReason)
                .containsExactly("素材 steps 条数 2 与骨架计划 3 不一致（条数以骨架为准）",
                        "素材 steps[1].usesAnchor='L9' 与骨架指派 'L2' 不一致（锚点只能领用骨架指派，不得自行改锚）");
    }

    @Test
    @DisplayName("T18/T19a：题干片失败清单落库——source=P1，重试通过后照常推进")
    void problemShardValidation_persistsProblems() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(genPipeline.generate(any(), anyList(), any()))
                .thenThrow(new ShardGenException("P1",
                        List.of("题干片 L1 段 2 内容被改写（保真红线）"), null))
                .thenReturn(JSON.readValue(CONTENT_JSON, ContentJson.class));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JobReviewError>> saved = ArgumentCaptor.forClass(List.class);
        verify(reviewErrorRepo, times(1)).saveAll(saved.capture());
        assertThat(saved.getAllValues().get(0))
                .extracting(JobReviewError::getJobId, JobReviewError::getSource, JobReviewError::getRound,
                        JobReviewError::getReason)
                .containsExactly(tuple(JOB_ID, "P1", 1, "题干片 L1 段 2 内容被改写（保真红线）"));
    }

    // ---- 19b. T18：驳回路由观测标注（分片路由进 stageHistory note） ----

    @Test
    @DisplayName("T18：V 驳回 note 携带分片路由摘要（describeRoute），QA 判负同")
    void rejectionNote_carriesShardRoutingSummary() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(genPipeline.describeRoute(anyList(), any())).thenReturn("P2");
        when(v4.validate(any()))
                .thenReturn(ValidationResult.fail(List.of("V1/x: 差异一")))
                .thenReturn(ValidationResult.ok());

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getStageHistory())
                .extracting(StageHistoryEntry::getNote)
                .anySatisfy(note -> assertThat(note).contains("V1-V4 驳回").contains("分片路由：P2"));
    }

    // ---- 20. T19a 读回归：重启续跑进 GENERATING 时从库读回（消除盲重试降级） ----

    @Test
    @DisplayName("T19a 读回归：GENERATING 驳回回环中断重启 → 最近清单从库读回注入重生成；盘上旧 content/audio 作废（I1 跨重启），题干从 content.json problem 段零成本恢复")
    void resumeIntoGenerating_readsBackLatestErrors_andRegenerates() throws Exception {
        Job job = claimedJob();
        job.enterStage(JobStatus.GENERATING, "驳回后重生成中断重启");
        stubRepo(job);
        presetResumeArtifacts();   // 上一轮盘上产物：content.json + audio_meta.json + wav（旧剧本/旧音频）
        JobReviewError latest = new JobReviewError(JOB_ID, "REVIEW", 2, "V1/x: 重启前驳回差异", LocalDateTime.now());
        when(reviewErrorRepo.findTopByJobIdOrderByIdDesc(JOB_ID)).thenReturn(Optional.of(latest));
        when(reviewErrorRepo.findByJobIdAndSourceAndRoundOrderByIdAsc(JOB_ID, "REVIEW", 2))
                .thenReturn(List.of(latest));
        when(genPipeline.generate(any(), anyList(), any()))
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
        when(renderWorker.render(any(Path.class), any())).thenReturn(artifactsFinal());
        when(qaFrameCheck.check(any(Path.class))).thenReturn(new QaFrameCheck.QaResult(true, List.of(), 3));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        // 盲重试消除：重生成收到的是库内读回的错误清单（分片流水线入参）
        verify(genPipeline).generate(any(), eq(List.of("V1/x: 重启前驳回差异")), any());
        // I1 跨重启：盘上旧 audio 作废 → TTS 真重录；QA 按当轮剧本现建工作区
        verify(ttsPipeline, times(1)).synthesizeAll(any(), any());
        verify(workspaceManager, times(1)).create(anyString(), any(), any(), any());
        // 题干恢复零成本：ExtractResult 从 content.json problem 段重建，审题工位不重调
        verify(extractStation, never()).extract(anyString());
    }

    @Test
    @DisplayName("T19a 读回归：盘上无 content.json 的驳回回环重启（首轮驳回即中断）→ 题干重新审题恢复 + 清单读回，生成不再是字面 null 盲跑")
    void resumeIntoGenerating_withoutContentJson_reextractsAndInjectsErrors() throws Exception {
        Job job = claimedJob();
        job.enterStage(JobStatus.GENERATING, "首轮驳回后重生成中断重启");
        stubRepo(job);
        JobReviewError latest = new JobReviewError(JOB_ID, "REVIEW", 1, "V1/x: 首轮驳回差异", LocalDateTime.now());
        when(reviewErrorRepo.findTopByJobIdOrderByIdDesc(JOB_ID)).thenReturn(Optional.of(latest));
        when(reviewErrorRepo.findByJobIdAndSourceAndRoundOrderByIdAsc(JOB_ID, "REVIEW", 1))
                .thenReturn(List.of(latest));
        stubStationsOk();   // extract/material/script/v1-v4/tts/qa 全正

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(extractStation).extract(job.getInputText());   // 重新审题恢复 ExtractResult
        verify(genPipeline).generate(any(), eq(List.of("V1/x: 首轮驳回差异")), any());
    }

    // ---- 22. T21：全局 job 墙钟死线（processingDeadlineAt——绝对死线：QUEUED 不计时/回环不刷新/重启持久） ----

    @Test
    @DisplayName("T21：poll 领单首次进 EXTRACTING 落库全局死线——自领单 now 起算≈默认 60min，QUEUED 排队等待不计入")
    void poll_firstExtracting_plantsWallClockDeadline_queuedWaitNotCounted() {
        Job job = queuedJob();
        job.setCreatedAt(LocalDateTime.now().minusMinutes(10));   // 排队已等 10min（批量积压场景）
        when(repo.findFirstByStatusOrderByCreatedAtAsc(JobStatus.QUEUED)).thenReturn(Optional.of(job));
        when(repo.save(any())).thenAnswer(returnsFirstArg());
        List<Runnable> submitted = new ArrayList<>();
        orchestrator.setJobExecutor(submitted::add);

        orchestrator.poll();

        assertThat(job.getStatus()).isEqualTo(JobStatus.EXTRACTING);
        assertThat(job.getProcessingDeadlineAt()).isNotNull();
        // 默认 60min 绑定（照 genDeadlineMinutes 窗口断言样式）：自领单 now 起算——若从 createdAt 起算只剩 50min，即判 QUEUED 被计时
        assertThat(job.getProcessingDeadlineAt()).isAfter(LocalDateTime.now().plusMinutes(58));
        assertThat(job.getProcessingDeadlineAt()).isBefore(LocalDateTime.now().plusMinutes(61));
        assertThat(submitted).hasSize(1);
    }

    @Test
    @DisplayName("T21：app.retry.wall-clock-deadline-minutes 默认绑定 60（超线即 FAILED 作废的配置基线）")
    void wallClockDeadlineMinutes_defaultIsSixty() {
        assertThat(new AppProperties().getRetry().getWallClockDeadlineMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("T21：超线在主循环检查点判 FAILED——lastError 逐字「全局墙钟超限（>60min），本题作废」，零阶段工位执行，回调 FAILED")
    void wallClockExceeded_loopCheckpoint_failsWithVerbatimLastError() throws Exception {
        Job job = claimedJob();
        job.setProcessingDeadlineAt(LocalDateTime.now().minusMinutes(1));   // 库里读回的过期死线（如停机期间过线）
        stubRepo(job);
        stubStationsOk();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getLastError()).isEqualTo("全局墙钟超限（>60min），本题作废");
        assertThat(job.getErrorMessage()).isEqualTo("全局墙钟超限（>60min），本题作废");
        verify(extractStation, never()).extract(anyString());   // 检查点在阶段执行前，工位零调用
        verify(genPipeline, never()).generate(any(), anyList(), any());
        verify(ttsPipeline, never()).synthesizeAll(any(), any());
        ArgumentCaptor<CallbackClient.CallbackPayload> payload = ArgumentCaptor.forClass(CallbackClient.CallbackPayload.class);
        verify(callbackClient).notify(eq(CALLBACK_URL), payload.capture());
        assertThat(payload.getValue().status()).isEqualTo("FAILED");
        assertThat(payload.getValue().error()).isEqualTo("全局墙钟超限（>60min），本题作废");
    }

    @Test
    @DisplayName("T21：驳回回环重进 GENERATING——genDeadlineAt 照旧刷新，全局死线【不】刷新（语义刻意相反，双断言并存）")
    void rejectionLoop_refreshesGenDeadline_butNotWallClockDeadline() throws Exception {
        Job job = claimedJob();
        LocalDateTime planted = LocalDateTime.now().plusMinutes(55);   // 首次 EXTRACTING 落下的库内值
        job.setProcessingDeadlineAt(planted);
        stubRepo(job);
        stubStationsOk();
        when(v4.validate(any()))
                .thenAnswer(inv -> {
                    job.setGenDeadlineAt(LocalDateTime.now().minusHours(1));   // 上一轮残留旧 gen 死线
                    return ValidationResult.fail(List.of("V1/x: 驳回一次"));
                })
                .thenReturn(ValidationResult.ok());

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(historyStages(job)).containsSubsequence(
                "GENERATING", "REVIEWING", "GENERATING", "REVIEWING");
        // 双断言：gen 死线重进刷新（T15b 语义不变）；全局死线原值不动（T21 裁定：回环不刷新）
        assertThat(job.getGenDeadlineAt()).isAfter(LocalDateTime.now().plusMinutes(29));
        assertThat(job.getProcessingDeadlineAt()).isEqualTo(planted);
    }

    @Test
    @DisplayName("T21：重启读回（sweep 周期兜底）——库里过期死线的非终态任务被 sweep 提交并在检查点判死，lastError 逐字 + 回调 FAILED")
    void wallClockSweep_killsOverdueJob_readBackFromDb() throws Exception {
        Job job = claimedJobAt(JobStatus.SPEAKING);
        job.setProcessingDeadlineAt(LocalDateTime.now().minusMinutes(1));   // 停机期间过线，重启后 sweep 读回
        stubRepo(job);
        orchestrator.setJobExecutor(Runnable::run);   // sweep 提交同步直跑（免真线程）
        when(repo.findByStatusNotIn(any())).thenReturn(List.of(job));

        orchestrator.wallClockSweep();

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getLastError()).isEqualTo("全局墙钟超限（>60min），本题作废");
        verify(ttsPipeline, never()).synthesizeAll(any(), any());   // 判死在检查点，阶段工位零调用
        ArgumentCaptor<CallbackClient.CallbackPayload> payload = ArgumentCaptor.forClass(CallbackClient.CallbackPayload.class);
        verify(callbackClient).notify(eq(CALLBACK_URL), payload.capture());
        assertThat(payload.getValue().status()).isEqualTo("FAILED");
        assertThat(payload.getValue().error()).isEqualTo("全局墙钟超限（>60min），本题作废");
    }

    @Test
    @DisplayName("T21：sweep 只提交过线任务——QUEUED 不计时（即使残留过期死线）与死线内任务均不提交")
    void wallClockSweep_submitsOnlyOverdueJobs() {
        List<Runnable> submitted = new ArrayList<>();
        orchestrator.setJobExecutor(submitted::add);
        Job overdueQueued = queuedJob();
        overdueQueued.setProcessingDeadlineAt(LocalDateTime.now().minusMinutes(1));   // 防御：QUEUED 即使残留过期死线也不计时
        Job withinDeadline = claimedJob();
        withinDeadline.setProcessingDeadlineAt(LocalDateTime.now().plusMinutes(30));
        Job overdue = claimedJobAt(JobStatus.GENERATING);
        overdue.setProcessingDeadlineAt(LocalDateTime.now().minusMinutes(1));
        when(repo.findByStatusNotIn(any())).thenReturn(List.of(overdueQueued, withinDeadline, overdue));

        orchestrator.wallClockSweep();

        assertThat(submitted).hasSize(1);
        assertThat(overdueQueued.getStatus()).isEqualTo(JobStatus.QUEUED);   // 未被 sweep 判死
        assertThat(withinDeadline.getStatus()).isEqualTo(JobStatus.EXTRACTING);
    }

    @Test
    @DisplayName("T21：死线内正常全链不受影响（回归）——全局死线全程原值不被改写，DONE 照旧")
    void withinWallClockDeadline_fullChainUnaffected() throws Exception {
        Job job = claimedJob();
        LocalDateTime planted = LocalDateTime.now().plusMinutes(59);
        job.setProcessingDeadlineAt(planted);
        stubRepo(job);
        stubStationsOk();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getProcessingDeadlineAt()).isEqualTo(planted);   // 任何阶段进入/回环都不改写全局死线
        verify(callbackClient).notify(eq(CALLBACK_URL), any());
    }

    // ---- 23. Task 22：DONE 后保留 TTS 行音频（存储清理补全——成片之外只留真实合成产物） ----

    private Path artifactsAudioDir() {
        return Path.of(props.getArtifactsDir()).resolve(JOB_ID).resolve("audio");
    }

    /**
     * Task 22 harness：mock 的 WorkspaceManager.cleanup 不动盘——桥接真删树（真实 WorkspaceManager
     * 按同一 props 定位 workspace/{jobId}；假工作区无 junction，ProcessRunner 不会被触碰），
     * 让「workspace 根已删」成为可断言的盘面事实。
     */
    private void stubCleanupDeletesWorkspaceForReal() throws Exception {
        WorkspaceManager real = new WorkspaceManager(mock(ProcessRunner.class), props);
        doAnswer(inv -> {
            real.cleanup(JOB_ID);
            return null;
        }).when(workspaceManager).cleanup(JOB_ID);
    }

    /** Task 22 harness：渲染替身真正落盘 final.mp4（mock 默认只返回路径不写盘），供「final.mp4 仍在」断言。 */
    private void stubRenderWritesFinalForReal() throws Exception {
        when(renderWorker.render(any(Path.class), any())).thenAnswer(inv -> {
            Files.createDirectories(artifactsFinal().getParent());
            Files.write(artifactsFinal(), RENDERED_MP4_BYTES);
            return artifactsFinal();
        });
    }

    /** 预置 workspace/{jobId}/public/audio/lines/line_01.wav（模拟 TTS 已把行音频写进工作区副本）。 */
    private void presetWorkspaceLinesWav() throws java.io.IOException {
        Files.createDirectories(wsPath().resolve("public/audio/lines"));
        Files.write(wsPath().resolve("public/audio/lines/line_01.wav"), WAV_BYTES);
    }

    private static final byte[] RENDERED_MP4_BYTES = {9, 8, 7, 6};

    @Test
    @DisplayName("T22：DONE → lines wav 保留至 artifacts/{id}/audio/lines/ 且内容=原文件，workspace 根已删，final.mp4 仍在（fixed 模板静态件不保留）")
    void done_preservesLinesWavInArtifacts_workspaceDeleted_finalUntouched() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        stubRenderWritesFinalForReal();
        stubCleanupDeletesWorkspaceForReal();
        presetWorkspaceLinesWav();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        Path preserved = artifactsAudioDir().resolve("lines").resolve("line_01.wav");
        assertThat(preserved).hasBinaryContent(WAV_BYTES);        // 内容 = 原合成文件
        assertThat(wsPath()).doesNotExist();                       // workspace 根已删
        assertThat(artifactsFinal()).hasBinaryContent(RENDERED_MP4_BYTES);   // final.mp4 路径零触碰
        assertThat(artifactsAudioDir().resolve("fixed")).doesNotExist();   // 范围裁定：模板静态件不保留
    }

    @Test
    @DisplayName("T22：开关=false → artifacts 无 audio、workspace 已删（现状回归，与旧行为完全一致）")
    void keepTtsAudioDisabled_noPreservation_legacyBehavior() throws Exception {
        props.getCleanup().setKeepTtsAudio(false);
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        stubRenderWritesFinalForReal();
        stubCleanupDeletesWorkspaceForReal();
        presetWorkspaceLinesWav();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(artifactsAudioDir()).doesNotExist();   // 不产生 audio 保留
        assertThat(wsPath()).doesNotExist();               // workspace 照删
        assertThat(artifactsFinal()).exists();
    }

    @Test
    @DisplayName("T22：源 lines 目录缺失（异常早夭路径）→ DONE 照常完成、无异常穿出、无保留、workspace 已删")
    void sourceLinesDirMissing_doneStillCompletes_noException() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        stubRenderWritesFinalForReal();
        stubCleanupDeletesWorkspaceForReal();
        // 不预置 lines wav（也不建 workspace 目录）——源目录不存在

        assertThatCode(() -> orchestrator.process(JOB_ID)).doesNotThrowAnyException();

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(artifactsAudioDir()).doesNotExist();   // 静默跳过，不制造空壳目录
        assertThat(wsPath()).doesNotExist();
        assertThat(artifactsFinal()).exists();
    }

    @Test
    @DisplayName("T22：移动失败注入（artifacts/{id}/audio 预置为同名文件迫使 Files.move 失败）→ DONE 照常、warn 落日志、workspace 照删、成片无恙")
    void moveFailure_warnLogged_doneAndCleanupUnaffected() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        stubRenderWritesFinalForReal();
        stubCleanupDeletesWorkspaceForReal();
        presetWorkspaceLinesWav();
        Files.createDirectories(artifactsAudioDir().getParent());
        Files.writeString(artifactsAudioDir(), "not-a-directory");   // audio 为普通文件 → move 必败

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(JobOrchestrator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            orchestrator.process(JOB_ID);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);   // 绝不影响 DONE 落库与回调语义
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getLevel())
                .isEqualTo(Level.WARN)
                .describedAs("warn 事件 %s", event.getFormattedMessage()));
        assertThat(appender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("TTS 行音频保留失败").contains(JOB_ID));
        assertThat(wsPath()).doesNotExist();               // workspace 照删
        assertThat(artifactsFinal()).hasBinaryContent(RENDERED_MP4_BYTES);   // 成片无恙
        verify(callbackClient).notify(eq(CALLBACK_URL), any());   // 回调照发
    }

    @Test
    @DisplayName("T22+T26：CANCELLED 路径——不产生 audio 保留；extracted.json 保留（T26 白名单扩三件套：取消也回看识图内容）")
    void cancelledPath_noTtsAudioPreserved() throws Exception {
        Job job = claimedJob();
        AtomicInteger reads = new AtomicInteger();
        when(repo.findById(JOB_ID)).thenAnswer(inv -> {
            if (reads.incrementAndGet() == 4) {
                job.setCancelRequested(true);   // GENERATING 之后、REVIEWING 之前取消落库
            }
            return Optional.of(job);
        });
        when(repo.save(any())).thenAnswer(returnsFirstArg());
        stubStationsOk();
        stubRenderWritesFinalForReal();
        stubCleanupDeletesWorkspaceForReal();
        presetWorkspaceLinesWav();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(artifactsExtracted()).exists();   // T26：识图结果保留（EXTRACT 成功即落盘，终态不清）
        assertThat(artifactsAudioDir()).doesNotExist();   // 不产 audio 保留
        assertThat(artifactsFinal()).doesNotExist();      // 无成片（渲染未发生）
        assertThat(wsPath()).doesNotExist();   // workspace 照删
        verify(workspaceManager).cleanup(JOB_ID);
    }

    @Test
    @DisplayName("T22：app.cleanup.keep-tts-audio 默认绑定 true（DONE 后保留 TTS 行音频的配置基线）")
    void cleanupKeepTtsAudio_defaultIsTrue() {
        assertThat(new AppProperties().getCleanup().isKeepTtsAudio()).isTrue();
    }

    // ---- 24. Task 26：识图结果落盘 artifacts/{id}/extracted.json（T22 白名单扩三件套） ----

    private Path artifactsExtracted() {
        return Path.of(props.getArtifactsDir()).resolve(JOB_ID).resolve("extracted.json");
    }

    @Test
    @DisplayName("T26：EXTRACT 成功 → artifacts/{id}/extracted.json 落盘，内容=ExtractResult 原样（problemType+lines/segments）")
    void extractSuccess_persistsExtractedJson() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(artifactsExtracted()).exists();
        assertThat(Files.readString(artifactsExtracted()))   // 原样 JSON 形状（非包装非转义）
                .contains("\"problemType\"").contains("\"lines\"").contains("\"segments\"");
        ExtractResult persisted = JSON.readValue(artifactsExtracted().toFile(), ExtractResult.class);
        assertThat(persisted).isEqualTo(EXTRACT);   // 反序列化回读 = 原对象（record 逐字段相等）
    }

    @Test
    @DisplayName("T26：断点续跑（盘上 content.json，ExtractResult 已丢）→ doReviewing 重建补写 extracted.json（幂等覆盖写）")
    void resumeIntoReviewing_rebuildsExtractedJson() throws Exception {
        Job job = claimedJobAt(JobStatus.REVIEWING);
        stubRepo(job);
        stubStationsOk();
        stubRenderWritesFinalForReal();
        presetResumeArtifacts();   // content.json + audio_meta.json + line wav（ExtractResult 不落库已丢）
        Files.createDirectories(artifactsExtracted().getParent());
        Files.writeString(artifactsExtracted(), "{\"stale\":true}");   // 旧内容将被幂等覆盖

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(Files.readString(artifactsExtracted())).doesNotContain("stale");
        ExtractResult persisted = JSON.readValue(artifactsExtracted().toFile(), ExtractResult.class);
        assertThat(persisted.problemType()).isEqualTo("计算题");   // 与 content.json problem 段同源
        assertThat(persisted.lines()).singleElement()
                .satisfies(l -> assertThat(l.id()).isEqualTo("L1"));
    }

    @Test
    @DisplayName("T26：GENERATING 断点重取审题（盘上无 content.json）→ ensureExtracted 重取后同步补写 extracted.json")
    void resumeIntoGenerating_reExtract_persistsExtractedJson() throws Exception {
        Job job = claimedJobAt(JobStatus.GENERATING);
        stubRepo(job);
        stubStationsOk();   // 不预置任何盘上断点产物 → ensureExtracted 走重取

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(extractStation, times(1)).extract(anyString());   // 确实走了重取
        ExtractResult persisted = JSON.readValue(artifactsExtracted().toFile(), ExtractResult.class);
        assertThat(persisted).isEqualTo(EXTRACT);
    }

    @Test
    @DisplayName("T26：落盘失败注入（extracted.json 预置为同名目录迫使写盘失败）→ 主流程无恙照常 DONE、warn 落日志")
    void persistExtractedFailure_doneStillCompletes_warnLogged() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        stubRenderWritesFinalForReal();
        Files.createDirectories(artifactsExtracted());   // 同名目录 → writeString 必败

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(JobOrchestrator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            orchestrator.process(JOB_ID);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);   // 尽力而为：绝不影响主流程
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("识图结果落盘失败").contains(JOB_ID));
        verify(callbackClient).notify(eq(CALLBACK_URL), any());   // 回调照发
    }
}
