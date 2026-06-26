package com.example.smartassistant.router.exception;

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
 * Router 全局异常处理器
 * <p>
 * 统一使用 {@link ApiResponse} 格式返回错误响应。
 * 错误码规范（ROUTER_ 前缀）：
 * <ul>
 *   <li>ROUTER_001: 参数校验失败</li>
 *   <li>ROUTER_002: 路由决策失败（无可用 Agent）</li>
 *   <li>ROUTER_003: Agent 调用失败</li>
 *   <li>ROUTER_004: 远程服务不可用</li>
 *   <li>ROUTER_005: 认证/鉴权失败</li>
 *   <li>ROUTER_099: 未知错误</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ===================== 参数校验异常 =====================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");

        log.warn("[ROUTER_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "ROUTER_001", msg, collectFieldErrors(e), getTraceId());
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

        log.warn("[ROUTER_001] 参数绑定失败 | path={} | msg={}", request.getRequestURI(), msg);

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "ROUTER_001", msg, null, getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), msg, error));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[ROUTER_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "ROUTER_001", e.getMessage(), null, getTraceId());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), e.getMessage(), error));
    }

    // ===================== 路由异常 =====================

    @ExceptionHandler(RoutingException.class)
    public ResponseEntity<ApiResponse<Void>> handleRoutingException(
            RoutingException e, HttpServletRequest request) {
        log.warn("[ROUTER_002] 路由失败 | path={} | agent={} | method={} | msg={}",
                request.getRequestURI(), e.getAgentName(), e.getRoutingMethod(), e.getMessage());

        String msg = e.getMessage() != null ? e.getMessage() : "路由失败，暂无可用服务";

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "ROUTER_002", msg, null, getTraceId());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), msg, error));
    }

    // ===================== Agent 调用异常 =====================

    @ExceptionHandler(AgentCallException.class)
    public ResponseEntity<ApiResponse<Void>> handleAgentCallException(
            AgentCallException e, HttpServletRequest request) {
        log.error("[ROUTER_003] Agent 调用失败 | path={} | agent={} | status={} | msg={}",
                request.getRequestURI(), e.getAgentName(), e.getHttpStatus(), e.getMessage());

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "ROUTER_003", e.getMessage(), null, getTraceId());
        return ResponseEntity.status(e.getHttpStatus()).body(
                ApiResponse.error(e.getHttpStatus(), "服务调用失败: " + e.getMessage(), error));
    }

    // ===================== 远程服务异常 =====================

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpServerError(
            HttpServerErrorException e, HttpServletRequest request) {
        log.error("[ROUTER_004] 远程服务错误 | path={} | status={} | msg={}",
                request.getRequestURI(), e.getStatusCode().value(), e.getMessage());

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "ROUTER_004", e.getMessage(), null, getTraceId());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ApiResponse.error(HttpStatus.BAD_GATEWAY.value(), "远程服务异常，请稍后重试", error));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceAccessException(
            ResourceAccessException e, HttpServletRequest request) {
        log.error("[ROUTER_004] 连接远程服务失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        String detail = extractServiceName(e.getMessage());

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "ROUTER_004", detail, null, getTraceId());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ApiResponse.error(HttpStatus.BAD_GATEWAY.value(), "服务暂时不可用，请稍后重试", error));
    }

    // ===================== 认证异常 =====================

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(
            SecurityException e, HttpServletRequest request) {
        log.warn("[ROUTER_005] 认证/鉴权失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "ROUTER_005", "认证失败，请重新登录", null, getTraceId());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "认证失败，请重新登录", error));
    }

    // ===================== 兜底异常 =====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(
            Exception e, HttpServletRequest request) {
        log.error("[ROUTER_099] 未处理异常 | path={} | type={} | msg={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "ROUTER_099", e.getClass().getSimpleName() + ": " + e.getMessage(), null, getTraceId());
        return ResponseEntity.internalServerError().body(
                ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部异常，请联系管理员", error));
    }

    // ===================== 工具方法 =====================

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
        return message;
    }

    private Map<String, String> collectFieldErrors(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(err ->
                fieldErrors.put(err.getField(), err.getDefaultMessage()));
        return fieldErrors.isEmpty() ? null : fieldErrors;
    }
}
