/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.exception;

import com.example.smartassistant.router.model.ErrorResponse;
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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Router 全局异常处理器
 * <p>
 * 统一所有 Controller 的异常处理，返回结构化错误响应。
 * 每个错误码以 ROUTER_ 开头，方便日志检索和监控告警。
 * <p>
 * 错误码规范：
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

    private static final String SERVICE = "router-service";

    // ===================== 参数校验异常 =====================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");

        log.warn("[ROUTER_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);

        ErrorResponse resp = ErrorResponse.of("ROUTER_001", msg, HttpStatus.BAD_REQUEST.value())
                .builder()
                .traceId(getTraceId())
                .detail("field: " + e.getBindingResult().getFieldErrors().get(0).getField())
                .build();

        return ResponseEntity.badRequest().body(resp);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBindException(
            BindException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数绑定失败");

        log.warn("[ROUTER_001] 参数绑定失败 | path={} | msg={}", request.getRequestURI(), msg);

        ErrorResponse resp = ErrorResponse.of("ROUTER_001", msg, HttpStatus.BAD_REQUEST.value())
                .builder()
                .traceId(getTraceId())
                .build();

        return ResponseEntity.badRequest().body(resp);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[ROUTER_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());

        ErrorResponse resp = ErrorResponse.of("ROUTER_001", e.getMessage(), HttpStatus.BAD_REQUEST.value())
                .builder()
                .traceId(getTraceId())
                .build();

        return ResponseEntity.badRequest().body(resp);
    }

    // ===================== 路由异常 =====================

    @ExceptionHandler(RoutingException.class)
    public ResponseEntity<ErrorResponse> handleRoutingException(
            RoutingException e, HttpServletRequest request) {
        log.warn("[ROUTER_002] 路由失败 | path={} | agent={} | method={} | msg={}",
                request.getRequestURI(), e.getAgentName(), e.getRoutingMethod(), e.getMessage());

        String msg = e.getMessage() != null ? e.getMessage() : "路由失败，暂无可用服务";

        ErrorResponse resp = ErrorResponse.of("ROUTER_002", msg, HttpStatus.SERVICE_UNAVAILABLE.value())
                .builder()
                .routingMethod(e.getRoutingMethod())
                .traceId(getTraceId())
                .build();

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(resp);
    }

    // ===================== Agent 调用异常 =====================

    @ExceptionHandler(AgentCallException.class)
    public ResponseEntity<ErrorResponse> handleAgentCallException(
            AgentCallException e, HttpServletRequest request) {
        log.error("[ROUTER_003] Agent 调用失败 | path={} | agent={} | status={} | msg={}",
                request.getRequestURI(), e.getAgentName(), e.getHttpStatus(), e.getMessage());

        ErrorResponse resp = ErrorResponse.of("ROUTER_003",
                        "服务调用失败: " + e.getMessage(),
                        e.getHttpStatus())
                .builder()
                .agentName(e.getAgentName())
                .traceId(getTraceId())
                .build();

        return ResponseEntity.status(e.getHttpStatus()).body(resp);
    }

    // ===================== 远程服务异常 =====================

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ErrorResponse> handleHttpServerError(
            HttpServerErrorException e, HttpServletRequest request) {
        log.error("[ROUTER_004] 远程服务错误 | path={} | status={} | msg={}",
                request.getRequestURI(), e.getStatusCode().value(), e.getMessage());

        ErrorResponse resp = ErrorResponse.of("ROUTER_004",
                        "远程服务异常，请稍后重试",
                        HttpStatus.BAD_GATEWAY.value())
                .builder()
                .traceId(getTraceId())
                .detail(e.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(resp);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleResourceAccessException(
            ResourceAccessException e, HttpServletRequest request) {
        log.error("[ROUTER_004] 连接远程服务失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        // 尝试提取目标服务名
        String detail = extractServiceName(e.getMessage());

        ErrorResponse resp = ErrorResponse.of("ROUTER_004",
                        "服务暂时不可用，请稍后重试",
                        HttpStatus.BAD_GATEWAY.value())
                .builder()
                .traceId(getTraceId())
                .detail(detail)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(resp);
    }

    // ===================== 认证异常 =====================

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurityException(
            Exception e, HttpServletRequest request) {
        log.warn("[ROUTER_005] 认证/鉴权失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        ErrorResponse resp = ErrorResponse.of("ROUTER_005",
                        "认证失败，请重新登录",
                        HttpStatus.UNAUTHORIZED.value())
                .builder()
                .traceId(getTraceId())
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(resp);
    }

    // ===================== 兜底异常 =====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(
            Exception e, HttpServletRequest request) {
        log.error("[ROUTER_099] 未处理异常 | path={} | type={} | msg={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);

        ErrorResponse resp = ErrorResponse.of("ROUTER_099",
                        "服务内部异常，请联系管理员",
                        HttpStatus.INTERNAL_SERVER_ERROR.value())
                .builder()
                .traceId(getTraceId())
                .detail(e.getClass().getSimpleName() + ": " + e.getMessage())
                .build();

        return ResponseEntity.internalServerError().body(resp);
    }

    // ===================== 工具方法 =====================

    private String getTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "unknown";
    }

    /**
     * 从异常消息中提取目标服务名
     */
    private String extractServiceName(String message) {
        if (message == null) return null;
        // 匹配形如 "I/O error on GET request for \"http://xxx/yyy\"" 的 URL
        Pattern p = Pattern.compile("(?:for |uri=)(https?://[^/\"]+)");
        Matcher m = p.matcher(message);
        if (m.find()) {
            return m.group(1);
        }
        return message;
    }
}
