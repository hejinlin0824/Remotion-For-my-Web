package com.wyf.factory.api.dto;

import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.StageHistoryEntry;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/v1/jobs/{id} 响应视图（spec §13）。
 * 刻意不包含 inputText / imageBase64 / callbackUrl：幂等载荷大且无必要回显，
 * 也不让 base64/题干全文出现在任何响应面（Global Constraint 4 泄漏面最小化）。
 */
public record JobView(String jobId, String status, String stage, String inputType, String aspect, String voice,
                      boolean cancelRequested, int extractRetries, int genRetries, int reviewRetries,
                      int ttsRetries, int qaRounds, String lastError, String errorMessage, String artifactsDir,
                      List<StageHistoryView> stageHistory, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static JobView from(Job j) {
        return new JobView(
                j.getId(),
                j.getStatus() == null ? null : j.getStatus().name(),
                j.getStage(),
                j.getInputType(),
                j.getAspect(),
                j.getVoice(),
                j.isCancelRequested(),
                j.getExtractRetries(),
                j.getGenRetries(),
                j.getReviewRetries(),
                j.getTtsRetries(),
                j.getQaRounds(),
                j.getLastError(),
                j.getErrorMessage(),
                j.getArtifactsDir(),
                j.getStageHistory() == null ? List.of()
                        : j.getStageHistory().stream().map(StageHistoryView::from).toList(),
                j.getCreatedAt(),
                j.getUpdatedAt());
    }

    /** stageHistory 条目视图（stage/state/note/at 四键） */
    public record StageHistoryView(String stage, String state, String note, LocalDateTime at) {

        public static StageHistoryView from(StageHistoryEntry e) {
            return new StageHistoryView(e.getStage(), e.getState(), e.getNote(), e.getAt());
        }
    }
}
