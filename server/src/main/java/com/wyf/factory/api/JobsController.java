package com.wyf.factory.api;

import com.wyf.factory.api.GlobalExceptionHandler.ApiException;
import com.wyf.factory.api.dto.BatchJobRequest;
import com.wyf.factory.api.dto.CreateJobRequest;
import com.wyf.factory.api.dto.JobView;
import com.wyf.factory.api.dto.ReviewErrorView;
import com.wyf.factory.domain.JobStatus;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST API v1（spec §13）六端点 + review-errors 观测端点（T19a）+ extracted 识图结果只读端点（T26）：
 * 入队 / 批量入队 / 单查 / 列表 / 取消 / 视频流 / 错误清单 / 识图结果。
 * 校验在本层手写（不引 validation 依赖）；业务判定在 {@link JobService}；错误统一 {error} 形状。
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobsController {

    private static final String ONLY_ASPECT = "16:9";   // Ruling-12：唯一画幅
    private static final String ONLY_VOICE = "Cherry";  // D6：v1 唯一音色
    /** T17：可选渲染档位白名单（缺省 1080p 由 service 层落） */
    private static final List<String> RESOLUTIONS = List.of("1080p", "720p");

    private final JobService service;

    public JobsController(JobService service) {
        this.service = service;
    }

    /** 1. 入队 → 202 {jobId} */
    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestBody CreateJobRequest req) {
        validate(req);
        return ResponseEntity.accepted().body(Map.of("jobId", service.create(req)));
    }

    /** 2. 批量入队 → 202 {jobIds}；items 空/含非法项 → 400 整批拒绝 */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, List<String>>> batch(@RequestBody BatchJobRequest req) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new ApiException(400, "items 不能为空");
        }
        req.items().forEach(this::validate); // 先整批预校验，任一非法即整批拒绝，不产生部分入队
        return ResponseEntity.accepted().body(Map.of("jobIds", service.createBatch(req.items())));
    }

    /** 3. 单查 → 200 JobView；不存在 → 404 */
    @GetMapping("/{id}")
    public JobView get(@PathVariable String id) {
        return service.get(id).orElseThrow(() -> new ApiException(404, "job not found"));
    }

    /** 4. 列表 → 200 {content,totalElements,page,size}；status 非法值 → 400 */
    @GetMapping
    public PageView list(@RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size) {
        JobStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = JobStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, "未知 status: " + status);
            }
        }
        if (page < 0 || size < 1) {
            throw new ApiException(400, "page/size 非法");
        }
        Page<JobView> result = service.list(filter, page, size);
        return new PageView(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    /** 5. 取消 → 202 已受理 / 409 SPEAKING 起不可取消 / 200 终态幂等 / 404 不存在 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable String id) {
        return switch (service.cancel(id)) {
            case ACCEPTED -> ResponseEntity.accepted().body(Map.of("jobId", id));
            case ALREADY_TERMINAL -> ResponseEntity.ok().body(Map.of("jobId", id));
            case NOT_CANCELLABLE -> ResponseEntity.status(409)
                    .body(Map.of("error", "SPEAKING 起不可取消，任务将继续至终态"));
            case NOT_FOUND -> ResponseEntity.status(404).body(Map.of("error", "job not found"));
        };
    }

    /** 6. 成片流 → 200 video/mp4（artifacts/{id}/final.mp4）；未就绪/无文件 → 404 */
    @GetMapping("/{id}/video")
    public ResponseEntity<Resource> video(@PathVariable String id) throws IOException {
        Path mp4 = service.videoPath(id).orElseThrow(() -> new ApiException(404, "video 未就绪"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(Files.size(mp4))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"final.mp4\"")
                .body(new FileSystemResource(mp4));
    }

    /** 7. 错误清单（T19a）→ 200 [{jobId,round,source,reason,createdAt}] 按 id 升序；job 不存在 → 404 */
    @GetMapping("/{id}/review-errors")
    public List<ReviewErrorView> reviewErrors(@PathVariable String id) {
        return service.reviewErrors(id);
    }

    /** 8. 识图结果（T26）→ 200 extracted.json 原体（application/json）；job 不存在/未落盘 → 404 {error} */
    @GetMapping("/{id}/extracted")
    public ResponseEntity<Resource> extracted(@PathVariable String id) throws IOException {
        Path json = service.extractedJson(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(Files.size(json))
                .body(new FileSystemResource(json));
    }

    /** 入队校验矩阵（spec §13 + Ruling-12 + D6），失败即 400 {error:原因} */
    private void validate(CreateJobRequest r) {
        if (r == null) {
            throw new ApiException(400, "请求体缺失");
        }
        String inputType = r.inputType();
        if (inputType == null || (!"TEXT".equals(inputType) && !"IMAGE".equals(inputType))) {
            throw new ApiException(400, "inputType 必须为 TEXT 或 IMAGE");
        }
        if ("TEXT".equals(inputType) && (r.text() == null || r.text().isBlank())) {
            throw new ApiException(400, "TEXT 输入缺少 text");
        }
        if ("IMAGE".equals(inputType) && (r.imageBase64() == null || r.imageBase64().isBlank())) {
            throw new ApiException(400, "IMAGE 输入缺少 imageBase64");
        }
        if (r.aspect() != null && !ONLY_ASPECT.equals(r.aspect())) {
            throw new ApiException(400, "aspect 仅支持 16:9（收到: " + r.aspect() + "）");
        }
        if (r.voice() != null && !ONLY_VOICE.equals(r.voice())) {
            throw new ApiException(400, "voice 仅支持 Cherry（收到: " + r.voice() + "）");
        }
        if (r.resolution() != null && !RESOLUTIONS.contains(r.resolution())) {
            throw new ApiException(400, "resolution 仅支持 1080p/720p（收到: " + r.resolution() + "）");
        }
    }

    /** 列表响应（spec §13 四键形状，不用 PageImpl 直序列化避免形状漂移） */
    record PageView(List<JobView> content, long totalElements, int page, int size) {
    }
}
