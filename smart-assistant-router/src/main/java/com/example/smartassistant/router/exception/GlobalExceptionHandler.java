package com.example.smartassistant.router.exception;

import com.example.smartassistant.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Router 全局异常处理器——仅处理模块独有异常
 * <p>
 * 公共异常类型（参数校验/认证/服务异常等）由 common 模块的
 * {@link com.example.smartassistant.common.exception.GlobalExceptionHandler} 统一处理。
 * <p>
 * 使用 {@code @Order(HIGHEST_PRECEDENCE)} 确保独有异常优先于 common 的兜底处理器。
 */
@Slf4j
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    // ===================== 工具方法 =====================

    private String getTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : null;
    }
}
