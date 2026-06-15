/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.exception;

import com.example.smartassistant.common.api.AgentApiResponse;
import com.example.smartassistant.common.api.AgentApiResponses;
import com.example.smartassistant.common.api.AgentError;
import com.example.smartassistant.common.monitoring.AgentErrorMetricsCollector;
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

import java.util.List;
import java.util.stream.Collectors;

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

    private final AgentErrorMetricsCollector metricsCollector;

    public GlobalExceptionHandler(AgentErrorMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    // ===================== 参数校验异常 =====================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        List<AgentError.ErrorDetail> details = e.getBindingResult().getFieldErrors().stream()
                .map(err -> new AgentError.ErrorDetail(
                        err.getField(),
                        err.getDefaultMessage(),
                        err.getRejectedValue()))
                .collect(Collectors.toList());
        String msg = details.stream()
                .map(d -> d.getField() + ": " + d.getMessage())
                .collect(Collectors.joining("; "));

        log.warn("[ROUTER_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);

        AgentError error = AgentError.builder()
                .code("ROUTER_001")
                .title("请求参数校验失败")
                .detail(msg)
                .status(HttpStatus.BAD_REQUEST.value())
                .instance(request.getRequestURI())
                .details(details)
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.badRequest().body(
                AgentApiResponses.error(error, getTraceId(), 0));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleBindException(
            BindException e, HttpServletRequest request) {
        List<AgentError.ErrorDetail> details = e.getBindingResult().getFieldErrors().stream()
                .map(err -> new AgentError.ErrorDetail(
                        err.getField(),
                        err.getDefaultMessage(),
                        err.getRejectedValue()))
                .collect(Collectors.toList());
        String msg = details.stream()
                .map(d -> d.getField() + ": " + d.getMessage())
                .collect(Collectors.joining("; "));

        log.warn("[ROUTER_001] 参数绑定失败 | path={} | msg={}", request.getRequestURI(), msg);

        AgentError error = AgentError.builder()
                .code("ROUTER_001")
                .title("请求参数绑定失败")
                .detail(msg)
                .status(HttpStatus.BAD_REQUEST.value())
                .instance(request.getRequestURI())
                .details(details)
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.badRequest().body(
                AgentApiResponses.error(error, getTraceId(), 0));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleIllegalArgumentException(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[ROUTER_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("ROUTER_001")
                .title("非法参数")
                .detail(e.getMessage())
                .status(HttpStatus.BAD_REQUEST.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.badRequest().body(
                AgentApiResponses.error(error, getTraceId(), 0));
    }

    // ===================== 路由异常 =====================

    @ExceptionHandler(RoutingException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleRoutingException(
            RoutingException e, HttpServletRequest request) {
        log.warn("[ROUTER_002] 路由失败 | path={} | agent={} | method={} | msg={}",
                request.getRequestURI(), e.getAgentName(), e.getRoutingMethod(), e.getMessage());

        String msg = e.getMessage() != null ? e.getMessage() : "路由失败，暂无可用服务";

        AgentError error = AgentError.builder()
                .code("ROUTER_002")
                .title("路由决策失败")
                .detail(msg)
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                AgentApiResponses.error(error, getTraceId(), 0, e.getAgentName()));
    }

    // ===================== Agent 调用异常 =====================

    @ExceptionHandler(AgentCallException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleAgentCallException(
            AgentCallException e, HttpServletRequest request) {
        log.error("[ROUTER_003] Agent 调用失败 | path={} | agent={} | status={} | msg={}",
                request.getRequestURI(), e.getAgentName(), e.getHttpStatus(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("ROUTER_003")
                .title("Agent 调用失败")
                .detail("服务调用失败: " + e.getMessage())
                .status(e.getHttpStatus())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(e.getHttpStatus()).body(
                AgentApiResponses.error(error, getTraceId(), 0, e.getAgentName()));
    }

    // ===================== 远程服务异常 =====================

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleHttpServerError(
            HttpServerErrorException e, HttpServletRequest request) {
        log.error("[ROUTER_004] 远程服务错误 | path={} | status={} | msg={}",
                request.getRequestURI(), e.getStatusCode().value(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("ROUTER_004")
                .title("远程服务异常")
                .detail("远程服务异常，请稍后重试")
                .status(HttpStatus.BAD_GATEWAY.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                AgentApiResponses.error(error, getTraceId(), 0));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleResourceAccessException(
            ResourceAccessException e, HttpServletRequest request) {
        log.error("[ROUTER_004] 连接远程服务失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("ROUTER_004")
                .title("服务暂时不可用")
                .detail("服务暂时不可用，请稍后重试")
                .status(HttpStatus.BAD_GATEWAY.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                AgentApiResponses.error(error, getTraceId(), 0));
    }

    // ===================== 认证异常 =====================

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleSecurityException(
            Exception e, HttpServletRequest request) {
        log.warn("[ROUTER_005] 认证/鉴权失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("ROUTER_005")
                .title("认证失败")
                .detail("认证失败，请重新登录")
                .status(HttpStatus.UNAUTHORIZED.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                AgentApiResponses.error(error, getTraceId(), 0));
    }

    // ===================== 兜底异常 =====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentApiResponse<Void>> handleGeneralException(
            Exception e, HttpServletRequest request) {
        log.error("[ROUTER_099] 未处理异常 | path={} | type={} | msg={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);

        AgentError error = AgentError.builder()
                .code("ROUTER_099")
                .title("服务内部异常")
                .detail("服务内部异常，请联系管理员")
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.internalServerError().body(
                AgentApiResponses.error(error, getTraceId(), 0));
    }

    // ===================== 工具方法 =====================

    private String getTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : null;
    }
}
