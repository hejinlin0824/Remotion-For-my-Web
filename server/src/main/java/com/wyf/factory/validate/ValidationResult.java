package com.wyf.factory.validate;

import java.util.ArrayList;
import java.util.List;

/**
 * 单级校验结果。
 *
 * @param pass       是否通过（只由阻断性错误决定）
 * @param errors     阻断性错误清单（T10 编排器回传 LLM 重试的素材），格式 "Vn/规则: 差异"
 * @param softErrors 非阻断提示（如 V4 内容过长预警），不计入 pass 判定，仅随错误清单披露
 */
public record ValidationResult(boolean pass, List<String> errors, List<String> softErrors) {

    public ValidationResult {
        errors = List.copyOf(errors);
        softErrors = List.copyOf(softErrors);
    }

    /** 常规构造：无 soft 提示。 */
    public ValidationResult(boolean pass, List<String> errors) {
        this(pass, errors, List.of());
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of(), List.of());
    }

    public static ValidationResult fail(List<String> errors) {
        return new ValidationResult(false, errors, List.of());
    }

    /** 合并多级结果：pass 取与，errors / softErrors 按序拼接。 */
    public static ValidationResult merge(ValidationResult... results) {
        List<String> errors = new ArrayList<>();
        List<String> soft = new ArrayList<>();
        boolean pass = true;
        for (ValidationResult r : results) {
            pass &= r.pass();
            errors.addAll(r.errors());
            soft.addAll(r.softErrors());
        }
        return new ValidationResult(pass, errors, soft);
    }
}
