package com.wyf.factory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.JobStatus;
import com.wyf.factory.domain.StageHistoryEntry;
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
import java.time.LocalDateTime;
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

        // 第 2/3 次素材与剧本生成的错误清单 = 上轮 QA FAIL 清单（与 V 驳回同一错误注入通道）
        ArgumentCaptor<List<String>> materialErrors = ArgumentCaptor.forClass(List.class);
        verify(materialStation, times(3)).generate(any(), materialErrors.capture());
        assertThat(materialErrors.getAllValues()).hasSize(3);
        assertThat(materialErrors.getAllValues().get(0)).isEmpty();
        assertThat(materialErrors.getAllValues().get(1)).containsExactly("FAIL s03 帧 12 结论卡公式等号后折行");
        assertThat(materialErrors.getAllValues().get(2)).containsExactly("FAIL s05 帧 40 卡片出缘");

        ArgumentCaptor<List<String>> scriptErrors = ArgumentCaptor.forClass(List.class);
        verify(scriptStation, times(3)).assemble(any(), any(), scriptErrors.capture());
        assertThat(scriptErrors.getAllValues().get(0)).isEmpty();
        assertThat(scriptErrors.getAllValues().get(1)).containsExactly("FAIL s03 帧 12 结论卡公式等号后折行");
        assertThat(scriptErrors.getAllValues().get(2)).containsExactly("FAIL s05 帧 40 卡片出缘");

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
        verify(materialStation, times(5)).generate(any(), anyList());
        verify(scriptStation, times(5)).assemble(any(), any(), anyList());
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
        when(scriptStation.assemble(any(), any(), anyList())).thenReturn(v1, v2);
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
        verifyNoInteractions(materialStation, scriptStation, ttsPipeline);
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
        verifyNoInteractions(extractStation, materialStation, scriptStation, ttsPipeline);
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
        when(materialStation.generate(any(), anyList())).thenReturn(MATERIAL);
        when(scriptStation.assemble(any(), any(), anyList()))
                .thenReturn(JSON.readValue(CONTENT_JSON, ContentJson.class));
        when(workspaceManager.create(eq(JOB_ID), any(), any(), any())).thenReturn(wsPath());
        when(renderWorker.render(any(Path.class), any())).thenReturn(artifactsFinal());
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

    // ---- 17. T15b②：GENERATING 重试墙钟死线 ----

    @Test
    @DisplayName("T15b②：墙钟超限 → 无视剩余次数直接 FAILED，lastError/errorMessage 注明「重试墙钟超限」，不再重试")
    void generating_wallClockExceeded_failsImmediatelyIgnoringBudget() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(materialStation.generate(any(), anyList())).thenAnswer(inv -> {
            // 模拟库内死线已过（如重启续跑后墙钟到点）：进 GENERATING 后死线为过去时刻
            job.setGenDeadlineAt(LocalDateTime.now().minusMinutes(1));
            throw new GlmException("GLM 请求 IO/超时失败（视为瞬态）", true);
        });

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getGenRetries()).isZero();   // 无视剩余次数：不进入计数路径
        assertThat(job.getErrorMessage()).contains("重试墙钟超限").contains("GLM 请求 IO/超时失败");
        assertThat(job.getLastError()).contains("重试墙钟超限");
        verify(materialStation, times(1)).generate(any(), anyList());
    }

    @Test
    @DisplayName("T15b②：墙钟未超限 → 既有计数逻辑不变（预算内就地重试至耗尽才 FAILED），且进 GENERATING 落库了死线")
    void generating_withinWallClock_countPathUnchanged_andDeadlinePersisted() throws Exception {
        Job job = claimedJob();
        stubRepo(job);
        stubStationsOk();
        when(materialStation.generate(any(), anyList()))
                .thenThrow(new GlmException("GLM 请求 IO/超时失败（视为瞬态）", true));

        orchestrator.process(JOB_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("重试 3 次预算耗尽").doesNotContain("墙钟");
        assertThat(job.getGenRetries()).isEqualTo(props.getRetry().getContentMax());
        verify(materialStation, times(props.getRetry().getContentMax() + 1)).generate(any(), anyList());
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
}
