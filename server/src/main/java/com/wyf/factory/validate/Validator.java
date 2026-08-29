package com.wyf.factory.validate;

/**
 * 校验链单级校验器（spec §10：V1 结构 → V2 题干保真 → V3 引用 → V4 语义）。
 * 实现为 Spring bean，由 T10 编排器按序消费；实现不得抛业务异常（V4 格式违规除外，
 * 见 V4Judge——模型没守输出格式按 retryable 处理）。
 */
public interface Validator {

    ValidationResult validate(ValidationContext ctx);
}
