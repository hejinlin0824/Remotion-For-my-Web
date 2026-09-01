package com.wyf.factory.api.dto;

/**
 * POST /api/v1/jobs/{id}/revise 请求体（T27 修改重审）。
 * record 直绑 Jackson；校验在 Controller 层手写（与 CreateJobRequest 同款，不引 validation 依赖）：
 * text 必填非空白且 ≤ 2000 码点——修改后的文本将作为新 TEXT 输入重审（用户裁定 2026-09-01）。
 */
public record ReviseRequest(String text) {
}
