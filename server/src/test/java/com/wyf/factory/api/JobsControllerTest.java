package com.wyf.factory.api;

import com.wyf.factory.api.dto.CreateJobRequest;
import com.wyf.factory.api.dto.JobView;
import com.wyf.factory.api.dto.ReviewErrorView;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.JobReviewError;
import com.wyf.factory.domain.JobStatus;
import com.wyf.factory.domain.StageHistoryEntry;
import com.wyf.factory.pipeline.JobOrchestrator;
import com.wyf.factory.repo.JobRepository;
import com.wyf.factory.repo.JobReviewErrorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 3 契约测试：spec §13 六端点各 1 正 1 反（Global Constraints：16:9 唯一画幅、
 * Cherry 唯一音色、错误响应统一 {error} 形状、不回显 inputText/imageBase64）。
 * Controller 层 @WebMvcTest + @MockBean JobService；Service 业务判定另见内嵌 ServiceLogic。
 */
@WebMvcTest(JobsController.class)
@DisplayName("REST API v1 六端点契约")
class JobsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    JobService service;

    // ---------------------------------------------------------------- POST /api/v1/jobs

    @Test
    @DisplayName("POST 合法 TEXT → 202 {jobId}，请求原样透传 service")
    void post_validText_returns202WithJobId() throws Exception {
        when(service.create(any())).thenReturn("uuid-1");

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inputType":"TEXT","text":"设 f(x)=x^3+ax^2+x 在 R 上单调递增，求 a"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("uuid-1"));

        ArgumentCaptor<CreateJobRequest> captor = ArgumentCaptor.forClass(CreateJobRequest.class);
        verify(service).create(captor.capture());
        assertThat(captor.getValue().inputType()).isEqualTo("TEXT");
        assertThat(captor.getValue().text()).contains("f(x)=x^3");
        assertThat(captor.getValue().aspect()).isNull();  // 缺省由 service 层落 "16:9"
        assertThat(captor.getValue().voice()).isNull();   // 缺省由 service 层落 "Cherry"
    }

    @Test
    @DisplayName("POST inputType=IMAGE 无 imageBase64 → 400，error 提 imageBase64")
    void post_imageWithoutImage_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inputType":"IMAGE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("imageBase64")));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST aspect=9:16 → 400（Ruling-12：16:9 唯一画幅），错误文案提 16:9")
    void post_aspect916_returns400Mentioning169() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inputType":"TEXT","text":"题目","aspect":"9:16"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("16:9")));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST inputType 缺失 → 400")
    void post_inputTypeMissing_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"题目"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("inputType")));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST voice=Alice → 400（v1 唯一音色 Cherry），错误文案提 Cherry")
    void post_voiceNotCherry_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inputType":"TEXT","text":"题目","voice":"Alice"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Cherry")));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST resolution=720p → 202，原样透传 service（T17：缺省校验在 service 层落 1080p）")
    void post_resolution720p_passthrough() throws Exception {
        when(service.create(any())).thenReturn("uuid-r");

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inputType":"TEXT","text":"题目","resolution":"720p"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("uuid-r"));

        ArgumentCaptor<CreateJobRequest> captor = ArgumentCaptor.forClass(CreateJobRequest.class);
        verify(service).create(captor.capture());
        assertThat(captor.getValue().resolution()).isEqualTo("720p");
    }

    @Test
    @DisplayName("POST resolution=4k → 400（T17：仅 1080p/720p），错误文案提 resolution")
    void post_resolutionInvalid_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inputType":"TEXT","text":"题目","resolution":"4k"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("resolution")));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST 请求体缺失/JSON 非法 → 400 {error}（HttpMessageNotReadable 统一处理）")
    void post_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ---------------------------------------------------------------- POST /api/v1/jobs/batch

    @Test
    @DisplayName("batch 两合法项 → 202 {jobIds:[..]}")
    void batch_twoValidItems_returns202WithJobIds() throws Exception {
        when(service.createBatch(any())).thenReturn(List.of("id-a", "id-b"));

        mockMvc.perform(post("/api/v1/jobs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"inputType":"TEXT","text":"题1"},{"inputType":"IMAGE","imageBase64":"AAAA"}]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobIds[0]").value("id-a"))
                .andExpect(jsonPath("$.jobIds[1]").value("id-b"));
    }

    @Test
    @DisplayName("batch 空 items → 400 整批拒绝")
    void batch_emptyItems_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("batch 含一个非法项 → 400 整批拒绝（不产生任何任务）")
    void batch_oneInvalidItem_rejectsWholeBatch() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"inputType":"TEXT","text":"题1"},{"inputType":"TEXT","text":"题2","aspect":"1:1"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("16:9")));
        verify(service, never()).createBatch(any());
    }

    @Test
    @DisplayName("batch 逐题独立 resolution：720p 与缺省混排 → 202，各自原样透传（T17）")
    void batch_perItemIndependentResolutions() throws Exception {
        when(service.createBatch(any())).thenReturn(List.of("id-a", "id-b"));

        mockMvc.perform(post("/api/v1/jobs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"inputType":"TEXT","text":"题1","resolution":"720p"},
                                          {"inputType":"TEXT","text":"题2"}]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobIds[0]").value("id-a"))
                .andExpect(jsonPath("$.jobIds[1]").value("id-b"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CreateJobRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).createBatch(captor.capture());
        assertThat(captor.getValue().get(0).resolution()).isEqualTo("720p");
        assertThat(captor.getValue().get(1).resolution()).isNull();
    }

    @Test
    @DisplayName("batch 一项 resolution 非法 → 400 整批拒绝，合法项也不入队（T17）")
    void batch_oneInvalidResolution_rejectsWholeBatch() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"inputType":"TEXT","text":"题1","resolution":"720p"},
                                          {"inputType":"TEXT","text":"题2","resolution":"1080"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("resolution")));
        verify(service, never()).createBatch(any());
    }

    // ---------------------------------------------------------------- GET /api/v1/jobs/{id}

    @Test
    @DisplayName("GET 存在 → 200 JobView，键集合齐全（含 stageHistory 条目四键与各计数）")
    void get_existing_returnsFullJobViewKeySet() throws Exception {
        when(service.get("j1")).thenReturn(java.util.Optional.of(JobView.from(fullJob())));

        mockMvc.perform(get("/api/v1/jobs/j1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("j1"))
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.stage").value("GENERATING"))
                .andExpect(jsonPath("$.inputType").value("TEXT"))
                .andExpect(jsonPath("$.aspect").value("16:9"))
                .andExpect(jsonPath("$.voice").value("Cherry"))
                .andExpect(jsonPath("$.resolution").value("1080p"))
                .andExpect(jsonPath("$.cancelRequested").value(false))
                .andExpect(jsonPath("$.extractRetries").value(1))
                .andExpect(jsonPath("$.genRetries").value(2))
                .andExpect(jsonPath("$.reviewRetries").value(3))
                .andExpect(jsonPath("$.ttsRetries").value(4))
                .andExpect(jsonPath("$.qaRounds").value(5))
                .andExpect(jsonPath("$.lastError").value("上一轮剧本被驳回"))
                .andExpect(jsonPath("$.errorMessage").value("终态原因占位"))
                .andExpect(jsonPath("$.artifactsDir").value("../artifacts/j1"))
                .andExpect(jsonPath("$.createdAt").value("2026-08-29T11:00:00"))
                .andExpect(jsonPath("$.updatedAt").value("2026-08-29T12:30:00"))
                .andExpect(jsonPath("$.stageHistory.length()").value(1))
                .andExpect(jsonPath("$.stageHistory[0].stage").value("EXTRACTING"))
                .andExpect(jsonPath("$.stageHistory[0].state").value("ENTER"))
                .andExpect(jsonPath("$.stageHistory[0].note").value("审题开始"))
                .andExpect(jsonPath("$.stageHistory[0].at").value("2026-08-29T12:00:00"));
    }

    @Test
    @DisplayName("GET 不存在 → 404 {error:'job not found'}")
    void get_missing_returns404() throws Exception {
        when(service.get("nope")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/jobs/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job not found"));
    }

    @Test
    @DisplayName("GET 不回显 inputText / imageBase64 全文（幂等载荷大且无必要）")
    void get_existing_neverEchoesInputPayloads() throws Exception {
        Job job = fullJob();
        job.setInputText("很长的题干全文……");
        when(service.get("j1")).thenReturn(java.util.Optional.of(JobView.from(job)));

        mockMvc.perform(get("/api/v1/jobs/j1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputText").doesNotExist())
                .andExpect(jsonPath("$.imageBase64").doesNotExist())
                .andExpect(jsonPath("$.callbackUrl").doesNotExist());
    }

    // ---------------------------------------------------------------- GET /api/v1/jobs?status=&page=&size=

    @Test
    @DisplayName("GET 列表缺省分页 page=0 size=20、无 status 过滤 → 200 {content,totalElements,page,size}")
    void list_defaultPagination() throws Exception {
        when(service.list(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(JobView.from(fullJob())), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].jobId").value("j1"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(service).list(null, 0, 20);
    }

    @Test
    @DisplayName("GET 列表 status=QUEUED 过滤 → 200，status 透传 service")
    void list_statusFilter() throws Exception {
        when(service.list(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/jobs").param("status", "QUEUED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty());

        verify(service).list(JobStatus.QUEUED, 0, 20);
    }

    @Test
    @DisplayName("GET 列表 status=BOGUS → 400")
    void list_unknownStatus_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/jobs").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("status")));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET 列表 page=abc → 400（参数类型非法）")
    void list_pageNotNumber_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/jobs").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ---------------------------------------------------------------- DELETE /api/v1/jobs/{id}

    @Test
    @DisplayName("DELETE 取消结果 ACCEPTED（QUEUED..REVIEWING）→ 202")
    void delete_accepted_returns202() throws Exception {
        when(service.cancel("j1")).thenReturn(JobService.CancelResult.ACCEPTED);

        mockMvc.perform(delete("/api/v1/jobs/j1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("j1"));
    }

    @Test
    @DisplayName("DELETE 取消结果 NOT_CANCELLABLE（SPEAKING：TTS 中途不可取消）→ 409 {error}")
    void delete_conflict_returns409() throws Exception {
        when(service.cancel("j1")).thenReturn(JobService.CancelResult.NOT_CANCELLABLE);

        mockMvc.perform(delete("/api/v1/jobs/j1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("DELETE 已终态（DONE/FAILED/CANCELLED）幂等 → 200")
    void delete_terminalIdempotent_returns200() throws Exception {
        when(service.cancel("j1")).thenReturn(JobService.CancelResult.ALREADY_TERMINAL);

        mockMvc.perform(delete("/api/v1/jobs/j1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("j1"));
    }

    @Test
    @DisplayName("DELETE 不存在 → 404 {error:'job not found'}")
    void delete_missing_returns404() throws Exception {
        when(service.cancel("j1")).thenReturn(JobService.CancelResult.NOT_FOUND);

        mockMvc.perform(delete("/api/v1/jobs/j1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job not found"));
    }

    // ---------------------------------------------------------------- GET /api/v1/jobs/{id}/video

    @Test
    @DisplayName("video 就绪 → 200 video/mp4 + Content-Disposition inline; filename=\"final.mp4\"，字节流原文")
    void video_ready_streamsMp4WithHeaders(@TempDir Path tempDir) throws Exception {
        Path mp4 = tempDir.resolve("final.mp4");
        Files.write(mp4, new byte[] {1, 2, 3, 4});
        when(service.videoPath("j1")).thenReturn(java.util.Optional.of(mp4));

        mockMvc.perform(get("/api/v1/jobs/j1/video"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("video/mp4")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"final.mp4\""))
                .andExpect(content().bytes(new byte[] {1, 2, 3, 4}));
    }

    @Test
    @DisplayName("video 未就绪/无文件 → 404 {error}")
    void video_notReady_returns404() throws Exception {
        when(service.videoPath("j1")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/jobs/j1/video"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("video 非 DONE（T12 F5）：service 404 成片未定版 → 404 {error} 契约形状不变")
    void video_notDone_mapsTo404ErrorShape() throws Exception {
        when(service.videoPath("j1"))
                .thenThrow(new GlobalExceptionHandler.ApiException(404, "成片未定版：任务 RENDERING 未达 DONE"));

        mockMvc.perform(get("/api/v1/jobs/j1/video"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("成片未定版：任务 RENDERING 未达 DONE"));
    }

    // ---------------------------------------------------------------- GET /api/v1/jobs/{id}/review-errors

    @Test
    @DisplayName("review-errors 有清单 → 200 [{jobId,round,source,reason,createdAt}]（T19a）")
    void reviewErrors_existing_returnsRows() throws Exception {
        when(service.reviewErrors("j1")).thenReturn(List.of(
                new ReviewErrorView("j1", 2, "REVIEW", "V1/x: 折行差异", LocalDateTime.of(2026, 8, 31, 10, 0))));

        mockMvc.perform(get("/api/v1/jobs/j1/review-errors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value("j1"))
                .andExpect(jsonPath("$[0].round").value(2))
                .andExpect(jsonPath("$[0].source").value("REVIEW"))
                .andExpect(jsonPath("$[0].reason").value("V1/x: 折行差异"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-08-31T10:00:00"));
    }

    @Test
    @DisplayName("review-errors 无清单 → 200 空数组（job 存在但从未被驳回）")
    void reviewErrors_noRows_returnsEmptyArray() throws Exception {
        when(service.reviewErrors("j1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/jobs/j1/review-errors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("review-errors job 不存在 → 404 {error:'job not found'}")
    void reviewErrors_missing_returns404() throws Exception {
        when(service.reviewErrors("nope")).thenThrow(new GlobalExceptionHandler.ApiException(404, "job not found"));

        mockMvc.perform(get("/api/v1/jobs/nope/review-errors"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job not found"));
    }

    // ---------------------------------------------------------------- GET /api/v1/jobs/{id}/extracted

    @Test
    @DisplayName("extracted 已落盘（T26）→ 200 application/json extracted.json 原体")
    void extracted_ready_returnsJsonBody(@TempDir Path tempDir) throws Exception {
        Path json = tempDir.resolve("extracted.json");
        Files.writeString(json, "{\"problemType\":\"计算题\",\"lines\":[]}");
        when(service.extractedJson("j1")).thenReturn(json);

        mockMvc.perform(get("/api/v1/jobs/j1/extracted"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"problemType\":\"计算题\",\"lines\":[]}"));
    }

    @Test
    @DisplayName("extracted 未落盘（T26：QUEUED/EXTRACTING 中/落盘失败）→ 404 {error:'识图结果未生成'}")
    void extracted_notPersisted_returns404() throws Exception {
        when(service.extractedJson("j1")).thenThrow(new GlobalExceptionHandler.ApiException(404, "识图结果未生成"));

        mockMvc.perform(get("/api/v1/jobs/j1/extracted"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("识图结果未生成"));
    }

    @Test
    @DisplayName("extracted job 不存在 → 404 {error:'job not found'}")
    void extracted_missingJob_returns404() throws Exception {
        when(service.extractedJson("nope")).thenThrow(new GlobalExceptionHandler.ApiException(404, "job not found"));

        mockMvc.perform(get("/api/v1/jobs/nope/extracted"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job not found"));
    }

    // ---------------------------------------------------------------- POST /api/v1/jobs/{id}/confirm

    @Test
    @DisplayName("confirm（T27）：AWAITING_CONFIRM → 202 {jobId} 异步续跑")
    void confirm_awaiting_returns202() throws Exception {
        when(service.confirm("j1")).thenReturn(JobOrchestrator.ConfirmResult.ACCEPTED);

        mockMvc.perform(post("/api/v1/jobs/j1/confirm"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("j1"));
    }

    @Test
    @DisplayName("confirm（T27）：非 AWAITING_CONFIRM → 409 {error}")
    void confirm_notAwaiting_returns409() throws Exception {
        when(service.confirm("j1")).thenReturn(JobOrchestrator.ConfirmResult.NOT_AWAITING_CONFIRM);

        mockMvc.perform(post("/api/v1/jobs/j1/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("confirm（T27）：任务不存在 → 404 {error:'job not found'}")
    void confirm_missing_returns404() throws Exception {
        when(service.confirm("nope")).thenReturn(JobOrchestrator.ConfirmResult.NOT_FOUND);

        mockMvc.perform(post("/api/v1/jobs/nope/confirm"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job not found"));
    }

    // ---------------------------------------------------------------- POST /api/v1/jobs/{id}/revise

    @Test
    @DisplayName("revise（T27）：AWAITING_CONFIRM + 合法 text → 202，text 原样透传 service")
    void revise_valid_returns202() throws Exception {
        when(service.revise(eq("j1"), eq(" 修改后的题目 "))).thenReturn(JobOrchestrator.ReviseResult.ACCEPTED);

        mockMvc.perform(post("/api/v1/jobs/j1/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\" 修改后的题目 \"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("j1"));

        verify(service).revise("j1", " 修改后的题目 ");   // 载荷原样透传（截断/清洗在决策层不做）
    }

    @Test
    @DisplayName("revise（T27）：text 缺失/空白 → 400 {error}，不触 service")
    void revise_blankText_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/j1/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        verifyNoInteractions(service);

        mockMvc.perform(post("/api/v1/jobs/j1/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("revise（T27）：text 超 2000 码点 → 400 {error}，不触 service")
    void revise_overlongText_returns400() throws Exception {
        String tooLong = "题".repeat(2001);

        mockMvc.perform(post("/api/v1/jobs/j1/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("2000")));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("revise（T27）：非 AWAITING_CONFIRM → 409；达修改上限 → 409 {error 提上限}")
    void revise_stateMatrix_409s() throws Exception {
        when(service.revise(eq("j1"), any())).thenReturn(JobOrchestrator.ReviseResult.NOT_AWAITING_CONFIRM);
        mockMvc.perform(post("/api/v1/jobs/j1/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());

        when(service.revise(eq("j1"), any())).thenReturn(JobOrchestrator.ReviseResult.LIMIT_EXCEEDED);
        mockMvc.perform(post("/api/v1/jobs/j1/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("上限")));
    }

    @Test
    @DisplayName("revise（T27）：任务不存在 → 404 {error:'job not found'}")
    void revise_missing_returns404() throws Exception {
        when(service.revise(eq("nope"), any())).thenReturn(JobOrchestrator.ReviseResult.NOT_FOUND);

        mockMvc.perform(post("/api/v1/jobs/nope/revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job not found"));
    }

    // ---------------------------------------------------------------- 兜底异常

    @Test
    @DisplayName("service 抛非预期异常 → 500 {error:'internal error'}，且响应不携带异常细节")
    void unexpectedException_returns500ErrorShape() throws Exception {
        when(service.get("j1")).thenThrow(new IllegalStateException("db exploded with 细节"));

        ResultActions result = mockMvc.perform(get("/api/v1/jobs/j1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal error"));

        assertThat(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("db exploded");
    }

    @Test
    @DisplayName("T15b① 定性证据：cancel 抛 ObjectOptimisticLockingFailureException（未兜住的读改写冲突）→ 兜底 500（R3 attempt3 现场 500 的成因通路）")
    void cancel_optimisticLockCollision_surfacesAs500() throws Exception {
        when(service.cancel("j1")).thenThrow(new ObjectOptimisticLockingFailureException(Job.class, "j1"));

        mockMvc.perform(delete("/api/v1/jobs/j1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal error"));
    }

    // ================================================================= Service 业务判定（薄封装，真实现 + mock repo）

    @Nested
    @DisplayName("JobService：入队缺省/取消判定/视频定位")
    class ServiceLogic {

        private final JobRepository repo = mock(JobRepository.class);
        private final JobReviewErrorRepository reviewErrorRepo = mock(JobReviewErrorRepository.class);
        private final JobOrchestrator orchestrator = mock(JobOrchestrator.class);
        private final AppProperties props = new AppProperties();
        private final JobService realService = new JobService(repo, reviewErrorRepo, orchestrator, props);

        @Test
        @DisplayName("T27：confirm/revise 薄委托编排器（决策+驱动单源在 JobOrchestrator），结果码透传")
        void confirmAndRevise_delegateToOrchestrator() {
            when(orchestrator.confirmAwaiting("j1")).thenReturn(JobOrchestrator.ConfirmResult.ACCEPTED);
            when(orchestrator.reviseAwaiting("j1", "新题目")).thenReturn(JobOrchestrator.ReviseResult.LIMIT_EXCEEDED);

            assertThat(realService.confirm("j1")).isEqualTo(JobOrchestrator.ConfirmResult.ACCEPTED);
            assertThat(realService.revise("j1", "新题目")).isEqualTo(JobOrchestrator.ReviseResult.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("T27：cancel AWAITING_CONFIRM → 直接 CANCELLED 终态落库 ACCEPTED（待确认态无 worker 收割标记，必须就地终态而非置位悬挂）")
        void cancel_awaitingConfirm_transitionsDirectlyToCancelled() {
            Job job = new Job();
            job.setInputType("IMAGE");
            job.setStatus(JobStatus.AWAITING_CONFIRM);
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(realService.cancel(job.getId())).isEqualTo(JobService.CancelResult.ACCEPTED);

            ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(JobStatus.CANCELLED);
        }

        @Test
        @DisplayName("create TEXT：落缺省 aspect=16:9 / voice=Cherry / resolution=1080p，id 为 UUID，artifactsDir 指向产物目录")
        void create_text_appliesDefaults() {
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            String id = realService.create(new CreateJobRequest("TEXT", "题目全文", null, null, null, null, "http://cb"));

            assertThat(id).hasSize(36);
            assertThat(UUID.fromString(id)).isNotNull();
            ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
            verify(repo).save(captor.capture());
            Job saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(JobStatus.QUEUED);
            assertThat(saved.getInputType()).isEqualTo("TEXT");
            assertThat(saved.getInputText()).isEqualTo("题目全文");
            assertThat(saved.getAspect()).isEqualTo("16:9");
            assertThat(saved.getVoice()).isEqualTo("Cherry");
            assertThat(saved.getResolution()).isEqualTo("1080p");
            assertThat(saved.getCallbackUrl()).isEqualTo("http://cb");
            assertThat(saved.getArtifactsDir()).isEqualTo("../artifacts/" + id);
        }

        @Test
        @DisplayName("create IMAGE：imageBase64 以 UTF-8 字节入库（new String 可无损还原 base64 文本供 GLM vision 用）")
        void create_image_storesBase64Bytes() {
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            String base64 = "iVBORw0KGgoAAAANSUhEUg==";

            realService.create(new CreateJobRequest("IMAGE", null, base64, "16:9", "Cherry", null, null));

            ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
            verify(repo).save(captor.capture());
            assertThat(new String(captor.getValue().getImageBase64(), StandardCharsets.UTF_8)).isEqualTo(base64);
            assertThat(captor.getValue().getInputText()).isNull();
        }

        @Test
        @DisplayName("create 显式 resolution=720p：原样落库（T17）")
        void create_explicit720p_stored() {
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            realService.create(new CreateJobRequest("TEXT", "题目全文", null, "16:9", "Cherry", "720p", null));

            ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue().getResolution()).isEqualTo("720p");
        }

        @Test
        @DisplayName("createBatch 逐题独立 resolution：720p 与 1080p 混排各落各的（T17 batch 语义）")
        void createBatch_perItemIndependentResolutions() {
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            realService.createBatch(List.of(
                    new CreateJobRequest("TEXT", "题1", null, null, null, "720p", null),
                    new CreateJobRequest("TEXT", "题2", null, null, null, "1080p", null),
                    new CreateJobRequest("TEXT", "题3", null, null, null, null, null)));

            ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
            verify(repo, times(3)).save(captor.capture());
            assertThat(captor.getAllValues()).extracting(Job::getResolution)
                    .containsExactly("720p", "1080p", "1080p");
        }

        @Test
        @DisplayName("cancel QUEUED → ACCEPTED，且落库 cancelRequested=true")
        void cancel_queued_marksCancelRequested() {
            Job job = new Job();
            job.setInputType("TEXT");
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(realService.cancel(job.getId())).isEqualTo(JobService.CancelResult.ACCEPTED);

            ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue().isCancelRequested()).isTrue();
        }

        @Test
        @DisplayName("cancel SPEAKING → NOT_CANCELLABLE（TTS 中途取消浪费已合成批次），不落库")
        void cancel_speaking_rejected() {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.SPEAKING);
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));

            assertThat(realService.cancel(job.getId())).isEqualTo(JobService.CancelResult.NOT_CANCELLABLE);
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("cancel RENDERING → ACCEPTED，且落库 cancelRequested=true（编排器渲染后收割）")
        void cancel_rendering_marksCancelRequested() {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.RENDERING);
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(realService.cancel(job.getId())).isEqualTo(JobService.CancelResult.ACCEPTED);

            ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue().isCancelRequested()).isTrue();
        }

        @Test
        @DisplayName("cancel QA → ACCEPTED，且落库 cancelRequested=true（编排器 QA 后收割）")
        void cancel_qa_marksCancelRequested() {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.QA);
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(realService.cancel(job.getId())).isEqualTo(JobService.CancelResult.ACCEPTED);

            ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue().isCancelRequested()).isTrue();
        }

        @Test
        @DisplayName("cancel 已终态（DONE）→ ALREADY_TERMINAL 幂等，不落库")
        void cancel_done_idempotent() {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.DONE);
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));

            assertThat(realService.cancel(job.getId())).isEqualTo(JobService.CancelResult.ALREADY_TERMINAL);
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("cancel 不存在 → NOT_FOUND")
        void cancel_missing_notFound() {
            when(repo.findById("nope")).thenReturn(java.util.Optional.empty());

            assertThat(realService.cancel("nope")).isEqualTo(JobService.CancelResult.NOT_FOUND);
        }

        @Test
        @DisplayName("T15b①：cancel 落库撞乐观锁（编排器 GENERATING 重试循环并发落库）→ 重读重试置位而非抛 OLE，仍 202+cancelRequested")
        void cancel_optimisticLockRace_retriesAndMarks() {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.GENERATING);
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));
            when(repo.save(any(Job.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Job.class, job.getId()))
                    .thenAnswer(inv -> inv.getArgument(0));

            assertThat(realService.cancel(job.getId())).isEqualTo(JobService.CancelResult.ACCEPTED);

            verify(repo, times(2)).findById(job.getId());   // 撞锁后重读最新行
            verify(repo, times(2)).save(any(Job.class));    // 首次被顶掉 + 重试成功
            assertThat(job.isCancelRequested()).isTrue();
        }

        @Test
        @DisplayName("T15b①：并发窗口内编排器把任务推进到 SPEAKING → 重读后按既有语义 409，不 500 不再落库")
        void cancel_raceMovesToSpeaking_returns409() {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.GENERATING);
            AtomicBoolean firstRead = new AtomicBoolean(true);
            when(repo.findById(job.getId())).thenAnswer(inv -> {
                if (firstRead.compareAndSet(true, false)) {
                    return java.util.Optional.of(job);
                }
                job.setStatus(JobStatus.SPEAKING);   // 并发窗口内编排器推进
                return java.util.Optional.of(job);
            });
            when(repo.save(any(Job.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Job.class, job.getId()));

            assertThat(realService.cancel(job.getId())).isEqualTo(JobService.CancelResult.NOT_CANCELLABLE);
            verify(repo, times(1)).save(any(Job.class));
        }

        @Test
        @DisplayName("T15b①：并发窗口内编排器把任务推到终态（DONE）→ 重读后按既有语义 200 幂等，不再置位")
        void cancel_raceMovesToTerminal_returns200() {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.GENERATING);
            AtomicBoolean firstRead = new AtomicBoolean(true);
            when(repo.findById(job.getId())).thenAnswer(inv -> {
                if (firstRead.compareAndSet(true, false)) {
                    return java.util.Optional.of(job);
                }
                job.setStatus(JobStatus.DONE);   // 并发窗口内编排器已完成归档
                return java.util.Optional.of(job);
            });
            when(repo.save(any(Job.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Job.class, job.getId()));

            assertThat(realService.cancel(job.getId())).isEqualTo(JobService.CancelResult.ALREADY_TERMINAL);
            verify(repo, times(1)).save(any(Job.class));   // 撞锁那一次被回滚，不再有第二次写（真库下不落任何标记）
        }

        @Test
        @DisplayName("T15b①：持续并发冲突（有界重试全撞）→ 语义化 503 {error} 兜底而非裸 OLE")
        void cancel_persistentRace_exhaustsAs503() {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.GENERATING);
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));
            when(repo.save(any(Job.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Job.class, job.getId()));

            assertThatThrownBy(() -> realService.cancel(job.getId()))
                    .isInstanceOf(GlobalExceptionHandler.ApiException.class)
                    .hasFieldOrPropertyWithValue("status", 503);
        }

        @Test
        @DisplayName("get：映射 JobView（jobId 透传）；不存在 → empty")
        void get_mapsToView() {
            Job job = new Job();
            job.setInputType("TEXT");
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));
            when(repo.findById("nope")).thenReturn(java.util.Optional.empty());

            assertThat(realService.get(job.getId())).hasValueSatisfying(v -> assertThat(v.jobId()).isEqualTo(job.getId()));
            assertThat(realService.get("nope")).isEmpty();
        }

        @Test
        @DisplayName("list：status=null 走 findAll，非空走 findByStatus")
        void list_routesByStatus() {
            when(repo.findAll(PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
            when(repo.findByStatus(JobStatus.FAILED, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            assertThat(realService.list(null, 0, 20).getTotalElements()).isZero();
            assertThat(realService.list(JobStatus.FAILED, 0, 20).getTotalElements()).isZero();

            verify(repo).findAll(PageRequest.of(0, 20));
            verify(repo).findByStatus(JobStatus.FAILED, PageRequest.of(0, 20));
        }

        @Test
        @DisplayName("videoPath DONE：final.mp4 存在 → 路径；不存在 → empty")
        void videoPath_done_checksFileExistence(@TempDir Path tempDir) throws Exception {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.DONE);
            job.setArtifactsDir(tempDir.toString());
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));

            assertThat(realService.videoPath(job.getId())).isEmpty(); // 未渲染无文件

            Files.write(tempDir.resolve("final.mp4"), new byte[] {9});
            assertThat(realService.videoPath(job.getId())).contains(tempDir.resolve("final.mp4"));
        }

        @Test
        @DisplayName("videoPath 非 DONE（T12 F5）：RENDERING 中即使旧 final.mp4 在盘也 404 成片未定版")
        void videoPath_notDone_throws404EvenIfStaleFileExists(@TempDir Path tempDir) throws Exception {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.RENDERING);
            job.setArtifactsDir(tempDir.toString());
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));
            Files.write(tempDir.resolve("final.mp4"), new byte[] {9});   // QA/重渲期间的在盘旧片

            assertThatThrownBy(() -> realService.videoPath(job.getId()))
                    .isInstanceOf(GlobalExceptionHandler.ApiException.class)
                    .hasMessageContaining("成片未定版")
                    .hasFieldOrPropertyWithValue("status", 404);
        }

        @Test
        @DisplayName("reviewErrors（T19a）：行按 id 升序映射视图；job 不存在 → 404 ApiException")
        void reviewErrors_mapsRows_missingJob404() {
            Job job = new Job();
            job.setInputType("TEXT");
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));
            when(repo.findById("nope")).thenReturn(java.util.Optional.empty());
            when(reviewErrorRepo.findByJobIdOrderByIdAsc(job.getId())).thenReturn(List.of(
                    new JobReviewError(job.getId(), "REVIEW", 1, "V1/x: 差异", LocalDateTime.of(2026, 8, 31, 9, 0)),
                    new JobReviewError(job.getId(), "QA", 1, "FAIL 折行", LocalDateTime.of(2026, 8, 31, 9, 5))));

            assertThat(realService.reviewErrors(job.getId()))
                    .extracting(ReviewErrorView::jobId, ReviewErrorView::source, ReviewErrorView::reason)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(job.getId(), "REVIEW", "V1/x: 差异"),
                            org.assertj.core.groups.Tuple.tuple(job.getId(), "QA", "FAIL 折行"));
            assertThatThrownBy(() -> realService.reviewErrors("nope"))
                    .isInstanceOf(GlobalExceptionHandler.ApiException.class)
                    .hasFieldOrPropertyWithValue("status", 404);
            verify(reviewErrorRepo, never()).findByJobIdOrderByIdAsc("nope");   // 404 短路，不查子表
        }

        @Test
        @DisplayName("extractedJson（T26）：文件在盘即返回路径（无 DONE 门禁，生成中已可读）；未落盘/任务不存在 → 404 ApiException")
        void extractedJson_locatesFile_missingAndNotPersisted404(@TempDir Path tempDir) throws Exception {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setStatus(JobStatus.GENERATING);   // 非 DONE：识图结果无定版门禁（与 videoPath 刻意不同）
            job.setArtifactsDir(tempDir.toString());
            when(repo.findById(job.getId())).thenReturn(java.util.Optional.of(job));
            when(repo.findById("nope")).thenReturn(java.util.Optional.empty());

            Files.writeString(tempDir.resolve("extracted.json"), "{\"problemType\":\"计算题\"}");
            assertThat(realService.extractedJson(job.getId())).isEqualTo(tempDir.resolve("extracted.json"));

            Files.delete(tempDir.resolve("extracted.json"));   // 未落盘（EXTRACTING 中/落盘失败）
            assertThatThrownBy(() -> realService.extractedJson(job.getId()))
                    .isInstanceOf(GlobalExceptionHandler.ApiException.class)
                    .hasMessageContaining("识图结果未生成")
                    .hasFieldOrPropertyWithValue("status", 404);

            assertThatThrownBy(() -> realService.extractedJson("nope"))
                    .isInstanceOf(GlobalExceptionHandler.ApiException.class)
                    .hasMessageContaining("job not found")
                    .hasFieldOrPropertyWithValue("status", 404);
        }

        @Test
        @DisplayName("extractedJson（T26）：artifactsDir 为 NULL 旧行 → 回退 props 目录定位，不 NPE")
        void extractedJson_nullArtifactsDir_fallsBackToProps(@TempDir Path tempDir) throws Exception {
            Job job = new Job();
            job.setInputType("TEXT");
            job.setId("legacy-1");
            when(repo.findById("legacy-1")).thenReturn(java.util.Optional.of(job));
            props.setArtifactsDir(tempDir.toString());   // 本嵌套类逐用例重建实例，props 设置不外溢

            assertThatThrownBy(() -> realService.extractedJson("legacy-1"))   // props 目录下无 extracted.json
                    .isInstanceOf(GlobalExceptionHandler.ApiException.class)
                    .hasMessageContaining("识图结果未生成");
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static Job fullJob() {
        Job job = new Job();
        job.setId("j1");
        job.setInputType("TEXT");
        job.setInputText("题干全文不应回显");
        job.setAspect("16:9");
        job.setVoice("Cherry");
        job.setCallbackUrl("http://callback.example/cb/1");
        job.setStatus(JobStatus.GENERATING);
        job.setStage("GENERATING");
        job.setCancelRequested(false);
        job.setExtractRetries(1);
        job.setGenRetries(2);
        job.setReviewRetries(3);
        job.setTtsRetries(4);
        job.setQaRounds(5);
        job.setLastError("上一轮剧本被驳回");
        job.setErrorMessage("终态原因占位");
        job.setArtifactsDir("../artifacts/j1");
        job.getStageHistory().clear();
        job.getStageHistory().add(new StageHistoryEntry(
                "EXTRACTING", StageHistoryEntry.STATE_ENTER, "审题开始", LocalDateTime.of(2026, 8, 29, 12, 0)));
        job.setCreatedAt(LocalDateTime.of(2026, 8, 29, 11, 0));
        job.setUpdatedAt(LocalDateTime.of(2026, 8, 29, 12, 30));
        return job;
    }
}
