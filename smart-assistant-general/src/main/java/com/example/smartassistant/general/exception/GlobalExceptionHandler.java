/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.exception;

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
 * General Agent 全局异常处理器
 * <p>
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

    private final AgentErrorMetricsCollector metricsCollector;

    public GlobalExceptionHandler(AgentErrorMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleValidation(
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
        log.warn("[GENERAL_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);

        AgentError error = AgentError.builder()
                .code("GENERAL_001")
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
        log.warn("[GENERAL_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("GENERAL_001")
                .title("非法参数")
                .detail(e.getMessage())
                .status(HttpStatus.BAD_REQUEST.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.badRequest().body(
                AgentApiResponses.error(error, null, 0));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentApiResponse<Void>> handleUnknown(
            Exception e, HttpServletRequest request) {
        log.error("[GENERAL_099] 未知错误 | path={} | msg={}", request.getRequestURI(), e.getMessage(), e);

        AgentError error = AgentError.builder()
                .code("GENERAL_099")
                .title("服务内部错误")
                .detail("服务内部错误，请联系管理员")
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AgentApiResponses.error(error, null, 0));
    }
}
