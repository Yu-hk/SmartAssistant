package com.example.smartassistant.general.exception;

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
 * General Agent 全局异常处理器
 * <p>
 * 统一使用 {@link ApiResponse} 格式返回错误响应。
 * 错误码规范（GENERAL_ 前缀）：
 * <ul>
 *   <li>GENERAL_001: 参数校验失败</li>
 *   <li>GENERAL_002: 工具执行失败（计算/新闻查询异常）</li>
 *   <li>GENERAL_003: Agent 推理失败</li>
 *   <li>GENERAL_099: 未知错误</li>
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
        log.warn("[GENERAL_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "GENERAL_001", msg, null, getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), msg, error));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[GENERAL_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "GENERAL_001", e.getMessage(), null, getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), e.getMessage(), error));
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
        log.error("[GENERAL_099] 未知错误 | path={} | msg={}", request.getRequestURI(), e.getMessage(), e);
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "GENERAL_099", e.getClass().getSimpleName() + ": " + e.getMessage(), null, getTraceId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部错误", error));
    }

    private String getTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : null;
    }
}
