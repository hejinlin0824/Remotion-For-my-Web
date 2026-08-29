package com.wyf.factory.api;

import com.wyf.factory.api.dto.CreateJobRequest;
import com.wyf.factory.api.dto.JobView;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.JobStatus;
import com.wyf.factory.repo.JobRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * REST 层对 Job 领域的薄封装（计划 Task 3）：入队 / 批量入队 / 查询 / 列表 / 取消 / 视频定位。
 * 校验（inputType/text/imageBase64/aspect/voice）在 Controller 层，这里只做业务判定与落库。
 */
@Service
public class JobService {

    /** DELETE /{id} 的语义化结果码，Controller 映射 202/409/200/404 */
    public enum CancelResult { ACCEPTED, NOT_CANCELLABLE, ALREADY_TERMINAL, NOT_FOUND }

    private final JobRepository repo;
    private final AppProperties props;

    public JobService(JobRepository repo, AppProperties props) {
        this.repo = repo;
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

    /** 列表：status 为 null 查全部，否则按状态过滤 */
    public Page<JobView> list(JobStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<Job> result = status == null ? repo.findAll(pageable) : repo.findByStatus(status, pageable);
        return result.map(JobView::from);
    }

    /**
     * 取消判定（spec §11：SPEAKING 前可取消，终态幂等）：
     * <ul>
     *   <li>QUEUED/EXTRACTING/GENERATING/REVIEWING → 置 cancelRequested=true 落库 → ACCEPTED（202）；</li>
     *   <li>SPEAKING/RENDERING/QA（SPEAKING 及之后非终态）→ NOT_CANCELLABLE（409）；</li>
     *   <li>DONE/FAILED/CANCELLED → ALREADY_TERMINAL（200 幂等）；</li>
     *   <li>id 不存在 → NOT_FOUND（404）。</li>
     * </ul>
     */
    public CancelResult cancel(String id) {
        Optional<Job> found = repo.findById(id);
        if (found.isEmpty()) {
            return CancelResult.NOT_FOUND;
        }
        Job job = found.get();
        return switch (job.getStatus()) {
            case QUEUED, EXTRACTING, GENERATING, REVIEWING -> {
                job.setCancelRequested(true);
                repo.save(job);
                yield CancelResult.ACCEPTED;
            }
            case SPEAKING, RENDERING, QA -> CancelResult.NOT_CANCELLABLE;
            case DONE, FAILED, CANCELLED -> CancelResult.ALREADY_TERMINAL;
        };
    }

    /** 成片定位：任务存在且 artifacts/{id}/final.mp4 已落盘才返回路径，否则 empty（未就绪 404）。 */
    public Optional<Path> videoPath(String id) {
        return repo.findById(id).flatMap(job -> {
            String dir = job.getArtifactsDir() != null
                    ? job.getArtifactsDir()
                    : props.getArtifactsDir() + "/" + job.getId();
            Path mp4 = Path.of(dir, "final.mp4");
            return Files.exists(mp4) ? Optional.of(mp4) : Optional.empty();
        });
    }
}
