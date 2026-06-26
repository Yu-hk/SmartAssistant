package com.example.smartassistant.consumer.exception;

import com.example.smartassistant.common.exception.ServiceException;
import com.example.smartassistant.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumer 全局异常处理器
 * <p>
 * 统一使用 {@link ApiResponse} 格式返回错误响应。
 * 错误码规范（CONSUMER_ 前缀）：
 * <ul>
 *   <li>CONSUMER_001: 参数校验失败</li>
 *   <li>CONSUMER_002: 远程服务调用失败（Router 不可用）</li>
 *   <li>CONSUMER_003: 会话/历史记录操作失败</li>
 *   <li>CONSUMER_004: 用户画像/MCP 操作失败</li>
 *   <li>CONSUMER_005: 认证/鉴权失败</li>
 *   <li>CONSUMER_099: 未知错误</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ========== 参数校验异常 ==========

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");

        log.warn("[CONSUMER_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "CONSUMER_001", msg, collectFieldErrors(e), getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), msg, error));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(
            BindException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数绑定失败");

        log.warn("[CONSUMER_001] 参数绑定失败 | path={}", request.getRequestURI());

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "CONSUMER_001", msg, collectFieldErrors(e), getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), msg, error));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[CONSUMER_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "CONSUMER_001", e.getMessage(), null, getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), e.getMessage(), error));
    }

    // ========== 远程服务调用异常 ==========

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpServerError(
            HttpServerErrorException e, HttpServletRequest request) {
        log.error("[CONSUMER_002] 远程服务错误 | path={} | status={}",
                request.getRequestURI(), e.getStatusCode().value());

        String detail = extractDetail(e.getMessage());
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "CONSUMER_002", detail, null, getTraceId());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ApiResponse.error(HttpStatus.BAD_GATEWAY.value(), "上游服务异常，请稍后重试", error));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceAccess(
            ResourceAccessException e, HttpServletRequest request) {
        log.error("[CONSUMER_002] 连接远程服务失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        String targetService = extractServiceName(e.getMessage());
        String msg = targetService != null
                ? "服务暂时不可用（" + targetService + "），请稍后重试"
                : "服务暂时不可用，请稍后重试";

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "CONSUMER_002", e.getMessage(), null, getTraceId());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ApiResponse.error(HttpStatus.BAD_GATEWAY.value(), msg, error));
    }

    // ========== 认证异常 ==========

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(
            SecurityException e, HttpServletRequest request) {
        log.warn("[CONSUMER_005] 认证失败 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "CONSUMER_005", "认证失败，请重新登录", null, getTraceId());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "认证失败，请重新登录", error));
    }

    // ========== ServiceException ==========

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleServiceException(
            ServiceException e, HttpServletRequest request) {
        log.warn("[SERVICE_EX] path={} | code={} | msg={}",
                request.getRequestURI(), e.getErrorCode(), e.getMessage());
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                e.getErrorCode(), e.getDetail(), null, getTraceId());
        return ResponseEntity.status(e.getHttpStatus()).body(
                ApiResponse.error(e.getHttpStatus(), e.getMessage(), error));
    }

    // ========== 兜底异常 ==========

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(
            Exception e, HttpServletRequest request) {
        log.error("[CONSUMER_099] 未处理异常 | path={} | type={} | msg={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "CONSUMER_099", e.getClass().getSimpleName() + ": " + e.getMessage(), null, getTraceId());
        return ResponseEntity.internalServerError().body(
                ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部异常，请联系管理员", error));
    }

    // ========== 工具方法 ==========

    private String getTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : null;
    }

    private String extractServiceName(String message) {
        if (message == null) return null;
        Pattern p = Pattern.compile("(?:for |uri=)(https?://[^/\"]+)");
        Matcher m = p.matcher(message);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String extractDetail(String message) {
        if (message == null) return null;
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private Map<String, String> collectFieldErrors(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(err ->
                fieldErrors.put(err.getField(), err.getDefaultMessage()));
        return fieldErrors.isEmpty() ? null : fieldErrors;
    }

    private Map<String, String> collectFieldErrors(BindException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(err ->
                fieldErrors.put(err.getField(), err.getDefaultMessage()));
        return fieldErrors.isEmpty() ? null : fieldErrors;
    }
}
