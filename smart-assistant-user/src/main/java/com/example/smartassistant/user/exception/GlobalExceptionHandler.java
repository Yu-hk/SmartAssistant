package com.example.smartassistant.user.exception;

import com.example.smartassistant.common.exception.ServiceException;
import com.example.smartassistant.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * User Service 全局异常处理器
 * <p>
 * 统一使用 {@link ApiResponse} 格式返回错误响应。
 * 错误码规范（USER_ 前缀）：
 * <ul>
 *   <li>USER_001: 参数校验失败</li>
 *   <li>USER_002: 认证失败（用户名/密码错误/Token无效）</li>
 *   <li>USER_003: 用户不存在</li>
 *   <li>USER_004: 账户冲突（重复注册等）</li>
 *   <li>USER_099: 未知错误</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("[USER_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "USER_001", msg, null, getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), msg, error));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[USER_002] 请求参数错误 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "USER_002", e.getMessage(), null, getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), e.getMessage(), error));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurity(
            SecurityException e, HttpServletRequest request) {
        log.warn("[USER_002] 认证失败 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "USER_002", "认证失败", null, getTraceId());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "认证失败", error));
    }

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(
            Exception e, HttpServletRequest request) {
        log.error("[USER_099] 未知错误 | path={} | msg={}", request.getRequestURI(), e.getMessage(), e);
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "USER_099", e.getClass().getSimpleName() + ": " + e.getMessage(), null, getTraceId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部错误", error));
    }

    private String getTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : null;
    }
}
