package com.wyf.factory.api.dto;

/**
 * POST /api/v1/jobs 请求体（spec §13）。
 * record 直绑 Jackson；校验在 Controller 层手写（不引 validation 依赖，计划 Task 3 Step 3）。
 *
 * <ul>
 *   <li>inputType 必填："TEXT" | "IMAGE"；</li>
 *   <li>text：TEXT 必填非空白；</li>
 *   <li>imageBase64：IMAGE 必填（base64 文本）；</li>
 *   <li>aspect：可空（缺省落 "16:9"），提供则必须等于 "16:9"（Ruling-12）；</li>
 *   <li>voice：可空（缺省落 "Cherry"），提供则必须等于 "Cherry"（v1 唯一音色）；</li>
 *   <li>callbackUrl：可空终态回调。</li>
 * </ul>
 */
public record CreateJobRequest(String inputType, String text, String imageBase64,
                               String aspect, String voice, String callbackUrl) {
}
