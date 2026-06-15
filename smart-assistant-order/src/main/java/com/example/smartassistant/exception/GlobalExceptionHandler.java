/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.exception;

import com.example.smartassistant.common.api.AgentApiResponse;
import com.example.smartassistant.common.api.AgentApiResponses;
import com.example.smartassistant.common.api.AgentError;
import com.example.smartassistant.common.monitoring.AgentErrorMetricsCollector;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Travel Agent 全局异常处理器
 * <p>
 * 错误码规范（TRAVEL_ 前缀）：
 * <ul>
 *   <li>TRAVEL_001: 参数校验失败</li>
 *   <li>TRAVEL_002: 工具执行失败（MCP 工具调用异常）</li>
 *   <li>TRAVEL_003: Agent 推理失败（ReAct 循环异常）</li>
 *   <li>TRAVEL_004: 数据访问异常（数据库/RAG 查询失败）</li>
 *   <li>TRAVEL_005: 权限不足（管理员操作拦截）</li>
 *   <li>TRAVEL_099: 未知错误</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final AgentErrorMetricsCollector metricsCollector;

    public GlobalExceptionHandler(AgentErrorMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    // ========== 参数校验 ==========

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

        log.warn("[TRAVEL_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);

        AgentError error = AgentError.builder()
                .code("TRAVEL_001")
                .title("请求参数校验失败")
                .detail(msg)
                .status(HttpStatus.BAD_REQUEST.value())
                .instance(request.getRequestURI())
                .details(details)
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.badRequest().body(
                AgentApiResponses.error(error, null, 0));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[TRAVEL_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("TRAVEL_001")
                .title("非法参数")
                .detail(e.getMessage())
                .status(HttpStatus.BAD_REQUEST.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.badRequest().body(
                AgentApiResponses.error(error, null, 0));
    }

    // ========== 数据访问异常 ==========

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleDataAccessException(
            org.springframework.dao.DataAccessException e, HttpServletRequest request) {
        log.error("[TRAVEL_004] 数据访问异常 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("TRAVEL_004")
                .title("数据访问异常")
                .detail("数据库暂时不可用，请稍后重试")
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                AgentApiResponses.error(error, null, 0));
    }

    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleResourceAccessException(
            org.springframework.web.client.ResourceAccessException e, HttpServletRequest request) {
        log.error("[TRAVEL_004] 远程资源访问失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("TRAVEL_004")
                .title("外部服务不可用")
                .detail("外部服务暂时不可用")
                .status(HttpStatus.BAD_GATEWAY.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                AgentApiResponses.error(error, null, 0));
    }

    // ========== 权限异常 ==========

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleSecurityException(
            SecurityException e, HttpServletRequest request) {
        log.warn("[TRAVEL_005] 权限不足 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("TRAVEL_005")
                .title("权限不足")
                .detail("权限不足: " + e.getMessage())
                .status(HttpStatus.FORBIDDEN.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                AgentApiResponses.error(error, null, 0));
    }

    // ========== 兜底异常 ==========

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentApiResponse<Void>> handleGeneralException(
            Exception e, HttpServletRequest request) {
        log.error("[TRAVEL_099] 未处理异常 | path={} | type={} | msg={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);

        AgentError error = AgentError.builder()
                .code("TRAVEL_099")
                .title("服务内部异常")
                .detail("服务内部异常，请联系管理员")
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.internalServerError().body(
                AgentApiResponses.error(error, null, 0));
    }
}
