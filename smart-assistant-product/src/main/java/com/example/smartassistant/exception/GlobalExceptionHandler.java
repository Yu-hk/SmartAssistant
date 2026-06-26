package com.example.smartassistant.exception;

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
 * Food Agent 全局异常处理器
 * <p>
 * 统一使用 {@link ApiResponse} 格式返回错误响应。
 * 错误码规范（FOOD_ 前缀）：
 * <ul>
 *   <li>FOOD_001: 参数校验失败</li>
 *   <li>FOOD_002: 工具执行失败（RAG 检索/餐厅查询异常）</li>
 *   <li>FOOD_003: Agent 推理失败（ReAct 循环异常）</li>
 *   <li>FOOD_004: 数据访问异常（数据库/向量存储查询失败）</li>
 *   <li>FOOD_005: 权限不足（管理员操作拦截）</li>
 *   <li>FOOD_099: 未知错误</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ========== 参数校验 ==========

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");

        log.warn("[FOOD_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "FOOD_001", msg, null, getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), msg, error));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[FOOD_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "FOOD_001", e.getMessage(), null, getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), e.getMessage(), error));
    }

    // ========== Agent 推理异常 ==========

    @ExceptionHandler(com.alibaba.cloud.ai.graph.exception.GraphStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleGraphStateException(
            com.alibaba.cloud.ai.graph.exception.GraphStateException e, HttpServletRequest request) {
        log.error("[FOOD_003] Agent 推理异常 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "FOOD_003", truncate(e.getMessage(), 300), null, getTraceId());
        return ResponseEntity.internalServerError().body(
                ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Agent 推理执行失败，请稍后重试", error));
    }

    // ========== 数据访问异常 ==========

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(
            org.springframework.dao.DataAccessException e, HttpServletRequest request) {
        log.error("[FOOD_004] 数据访问异常 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "FOOD_004", truncate(e.getMessage(), 200), null, getTraceId());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "数据库暂时不可用，请稍后重试", error));
    }

    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceAccessException(
            org.springframework.web.client.ResourceAccessException e, HttpServletRequest request) {
        log.error("[FOOD_004] 远程资源访问失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "FOOD_004", e.getMessage(), null, getTraceId());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ApiResponse.error(HttpStatus.BAD_GATEWAY.value(), "外部服务暂时不可用", error));
    }

    // ========== 权限异常 ==========

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(
            SecurityException e, HttpServletRequest request) {
        log.warn("[FOOD_005] 权限不足 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        String msg = "权限不足: " + e.getMessage();
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "FOOD_005", msg, null, getTraceId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResponse.error(HttpStatus.FORBIDDEN.value(), msg, error));
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
        log.error("[FOOD_099] 未处理异常 | path={} | type={} | msg={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "FOOD_099", e.getClass().getSimpleName() + ": " + e.getMessage(), null, getTraceId());
        return ResponseEntity.internalServerError().body(
                ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部异常，请联系管理员", error));
    }

    // ========== 工具方法 ==========

    private String getTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : null;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
