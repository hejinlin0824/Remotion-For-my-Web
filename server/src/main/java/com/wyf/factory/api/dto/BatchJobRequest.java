package com.wyf.factory.api.dto;

import java.util.List;

/**
 * POST /api/v1/jobs/batch 请求体（spec §13）：items 为 N 个 CreateJobRequest。
 * items 缺失或空，或任一项非法 → 400 整批拒绝（不产生部分入队）。
 */
public record BatchJobRequest(List<CreateJobRequest> items) {
}
