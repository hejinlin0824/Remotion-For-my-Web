package com.wyf.factory.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * 统一错误响应：一律 { "error": "..." } 形状（spec §13/§14）。
 *
 * <p><b>日志红线（Global Constraint 4）</b>：请求体（含截图 base64、题干全文）与 key 绝不进日志。
 * 因此 HttpMessageNotReadable 一类可能携带请求体片段的异常只记异常类名与 URI，不记 message；
 * 兜底 500 也只记 URI + 栈（栈来自服务端代码，非请求载荷）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 便捷业务异常：status + message → 对应 HTTP 状态码与 {error} 响应 */
    public static class ApiException extends RuntimeException {

        private final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    /** 请求形状错误 → 400（message 一律服务端措辞，不回显请求载荷） */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception e) {
        String message = "请求体缺失或 JSON 非法";
        if (e instanceof MethodArgumentTypeMismatchException t) {
            message = "参数类型非法: " + t.getName();
        } else if (e instanceof MissingServletRequestParameterException m) {
            message = "缺少参数: " + m.getParameterName();
        }
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /** 兜底 500：响应不携带异常细节，日志只记 URI（请求体绝不入日志） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未处理异常 uri={}", request.getRequestURI(), e);
        return ResponseEntity.internalServerError().body(Map.of("error", "internal error"));
    }
}
