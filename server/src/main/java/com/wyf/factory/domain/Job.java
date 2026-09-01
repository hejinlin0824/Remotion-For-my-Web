package com.wyf.factory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 讲题任务实体（jobs 表）。语义来源 spec §11：状态机 + 队列 + 断点续跑。
 *
 * <ul>
 *   <li>id：UUID，代码生成（构造器/@PrePersist 兜底）；</li>
 *   <li>status：非终态默认 QUEUED；阶段推进只走 {@link #enterStage}（校验状态机）；</li>
 *   <li>stage：当前阶段描述，v1 与 status.name() 同步；</li>
 *   <li>stageHistory：JSON 存 CLOB（{@link StageHistoryEntry.StageHistoryConverter}）；</li>
 *   <li>createdAt/updatedAt：手工审计（@PrePersist/@PreUpdate），不用 Spring Data auditing；</li>
 *   <li>version：@Version 乐观锁——领单协议的抢占依据（见 JobRepository javadoc）。</li>
 * </ul>
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @Column(length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private JobStatus status;

    /** 当前阶段描述，v1 与 status 同步 */
    @Column(length = 20)
    private String stage;

    /** "TEXT" | "IMAGE" */
    @Column(length = 10, nullable = false)
    private String inputType;

    /** 文本题干（TEXT 路径） */
    @Lob
    private String inputText;

    /** 截图 base64（IMAGE 路径，可空） */
    @Lob
    private byte[] imageBase64;

    /** 画幅，唯一合法值 "16:9"（Global Constraint 2） */
    @Column(length = 10)
    private String aspect;

    @Column(length = 50)
    private String voice;

    /**
     * 渲染档位（T17）："1080p"（默认，原生 1920×1080 母版）| "720p"（--scale=2/3 等比 1280×720，
     * 模板/composition 零改动）。落库字段：断点续跑/重渲必须从这里读，不能只存内存。
     * 字段初始化缺省 1080p：golden 体系默认路径不动；库里升级窗口期的 NULL 旧行由
     * RenderWorker 的 null 容错按 1080p 处理。
     */
    @Column(length = 10)
    private String resolution = "1080p";

    private String callbackUrl;

    /** 取消标记：阶段间检查点发现即停（SPEAKING 起不可取消） */
    private boolean cancelRequested;

    /** 各阶段重试计数（spec §10 上限） */
    private int extractRetries;
    private int genRetries;
    private int reviewRetries;
    private int ttsRetries;
    private int qaRounds;

    /**
     * 识图结果「修改重审」（revise）已用次数（T27 防刷）：用户在 AWAITING_CONFIRM 提交修改文本
     * 转 TEXT 重审时 +1；达到 app.pipeline.max-revise（默认 10）后 revise 请求 409 拒绝
     * （不烧任务——用户驱动 ≠ 系统重试，与 extractRetries 完全独立）。
     * Hibernate 自动 DDL 建列；H2 文件库老行默认 0。
     */
    private int reviseCount;

    /** 最近一次阶段内错误（可重试路径） */
    @Lob
    private String lastError;

    /**
     * GENERATING 墙钟死线（T15b②）：每次进入 GENERATING 落库 now+配置值；retryOrFail 先查墙钟，
     * 超线无视剩余次数直接 failJob（R3 attempt3 实证：纯次数预算在 GLM 网络病态时挂 14h+）。
     * 合法驳回回环（V/QA 判负→GENERATING）重进时刷新；断点续跑（sweep）从库读回仍生效；
     * NULL=无死线（未进过 GENERATING 或升级窗口期旧行），按既有计数逻辑。
     */
    private LocalDateTime genDeadlineAt;

    /**
     * 全局处理墙钟死线（T21）：首次进入 EXTRACTING 落库 now + 配置值（app.retry.wall-clock-deadline-minutes，
     * 默认 60min），此后<b>永不刷新</b>——与 {@link #genDeadlineAt} 的「重进刷新」刻意相反：
     * 全局死线的意义就是掐断磨蹭，V/QA 判负回环不重置时钟。QUEUED 排队等待不计时
     * （排队延迟是容量问题，不是单题超时——批量公平性）；死线在库里 → 重启持久，
     * sweep 续跑读回仍生效（停机期间过线的任务由 sweep 判死）。超线处置：非终态 +
     * now > 死线 → 终态 FAILED（lastError=「全局墙钟超限（&gt;Nmin），本题作废」）。
     * 诚实边界：单个长调用（如渲染）进行中超线，要等该调用结束到达转换点才判死
     * ——渲染子进程自有 30min spawn 硬界兜底。NULL=未进过 EXTRACTING 或升级窗口旧行，不计时。
     */
    private LocalDateTime processingDeadlineAt;

    /** 终态原因（FAILED/CANCELLED 时写入） */
    @Lob
    private String errorMessage;

    private String artifactsDir;

    @Convert(converter = StageHistoryEntry.StageHistoryConverter.class)
    @Column(columnDefinition = "CLOB")
    private List<StageHistoryEntry> stageHistory = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private long version;

    public Job() {
        this.id = UUID.randomUUID().toString();
        this.status = JobStatus.QUEUED;
        this.stage = this.status.name();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
        this.stageHistory.add(new StageHistoryEntry(
                this.status.name(), StageHistoryEntry.STATE_ENTER, "入队", this.createdAt));
    }

    /**
     * 进入下一阶段：校验 {@link JobStatus#canTransit}，非法迁移抛 {@link IllegalStateException}；
     * 合法则同步 status/stage/updatedAt 并追加一条 ENTER 历史。
     */
    public void enterStage(JobStatus to, String note) {
        if (!JobStatus.canTransit(this.status, to)) {
            throw new IllegalStateException("非法状态迁移: " + this.status + " -> " + to);
        }
        this.status = to;
        this.stage = to.name();
        this.updatedAt = LocalDateTime.now();
        this.stageHistory.add(new StageHistoryEntry(to.name(), StageHistoryEntry.STATE_ENTER, note, this.updatedAt));
    }

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = JobStatus.QUEUED;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getInputType() { return inputType; }
    public void setInputType(String inputType) { this.inputType = inputType; }
    public String getInputText() { return inputText; }
    public void setInputText(String inputText) { this.inputText = inputText; }
    public byte[] getImageBase64() { return imageBase64; }
    public void setImageBase64(byte[] imageBase64) { this.imageBase64 = imageBase64; }
    public String getAspect() { return aspect; }
    public void setAspect(String aspect) { this.aspect = aspect; }
    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public boolean isCancelRequested() { return cancelRequested; }
    public void setCancelRequested(boolean cancelRequested) { this.cancelRequested = cancelRequested; }
    public int getExtractRetries() { return extractRetries; }
    public void setExtractRetries(int extractRetries) { this.extractRetries = extractRetries; }
    public int getGenRetries() { return genRetries; }
    public void setGenRetries(int genRetries) { this.genRetries = genRetries; }
    public int getReviewRetries() { return reviewRetries; }
    public void setReviewRetries(int reviewRetries) { this.reviewRetries = reviewRetries; }
    public int getTtsRetries() { return ttsRetries; }
    public void setTtsRetries(int ttsRetries) { this.ttsRetries = ttsRetries; }
    public int getQaRounds() { return qaRounds; }
    public void setQaRounds(int qaRounds) { this.qaRounds = qaRounds; }
    public int getReviseCount() { return reviseCount; }
    public void setReviseCount(int reviseCount) { this.reviseCount = reviseCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getGenDeadlineAt() { return genDeadlineAt; }
    public void setGenDeadlineAt(LocalDateTime genDeadlineAt) { this.genDeadlineAt = genDeadlineAt; }
    public LocalDateTime getProcessingDeadlineAt() { return processingDeadlineAt; }
    public void setProcessingDeadlineAt(LocalDateTime processingDeadlineAt) { this.processingDeadlineAt = processingDeadlineAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getArtifactsDir() { return artifactsDir; }
    public void setArtifactsDir(String artifactsDir) { this.artifactsDir = artifactsDir; }
    public List<StageHistoryEntry> getStageHistory() { return stageHistory; }
    public void setStageHistory(List<StageHistoryEntry> stageHistory) { this.stageHistory = stageHistory; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
}
