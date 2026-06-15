/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.exception;

import com.example.smartassistant.common.api.AgentApiResponse;
import com.example.smartassistant.common.api.AgentApiResponses;
import com.example.smartassistant.common.api.AgentError;
import com.example.smartassistant.common.monitoring.AgentErrorMetricsCollector;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Consumer 全局异常处理器
 * <p>
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

    private final AgentErrorMetricsCollector metricsCollector;

    public GlobalExceptionHandler(AgentErrorMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    // ========== 参数校验异常 ==========

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

        log.warn("[CONSUMER_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);

        AgentError error = AgentError.builder()
                .code("CONSUMER_001")
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

        log.warn("[CONSUMER_001] 参数绑定失败 | path={}", request.getRequestURI());

        AgentError error = AgentError.builder()
                .code("CONSUMER_001")
                .title("请求参数绑定失败")
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
        log.warn("[CONSUMER_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("CONSUMER_001")
                .title("非法参数")
                .detail(e.getMessage())
                .status(HttpStatus.BAD_REQUEST.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.badRequest().body(
                AgentApiResponses.error(error, null, 0));
    }

    // ========== 远程服务调用异常 ==========

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleHttpServerError(
            HttpServerErrorException e, HttpServletRequest request) {
        log.error("[CONSUMER_002] 远程服务错误 | path={} | status={}",
                request.getRequestURI(), e.getStatusCode().value());

        AgentError error = AgentError.builder()
                .code("CONSUMER_002")
                .title("上游服务异常")
                .detail("上游服务异常，请稍后重试")
                .status(HttpStatus.BAD_GATEWAY.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                AgentApiResponses.error(error, null, 0));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<AgentApiResponse<Void>> handleResourceAccess(
            ResourceAccessException e, HttpServletRequest request) {
        log.error("[CONSUMER_002] 连接远程服务失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        String targetService = extractServiceName(e.getMessage());
        String msg = targetService != null
                ? "服务暂时不可用（" + targetService + "），请稍后重试"
                : "服务暂时不可用，请稍后重试";

        AgentError error = AgentError.builder()
                .code("CONSUMER_002")
                .title("远程服务不可用")
                .detail(msg)
                .status(HttpStatus.BAD_GATEWAY.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                AgentApiResponses.error(error, null, 0));
    }

    // ========== 认证异常 ==========

    @ExceptionHandler({SecurityException.class})
    public ResponseEntity<AgentApiResponse<Void>> handleSecurityException(
            SecurityException e, HttpServletRequest request) {
        log.warn("[CONSUMER_005] 认证失败 | path={} | msg={}", request.getRequestURI(), e.getMessage());

        AgentError error = AgentError.builder()
                .code("CONSUMER_005")
                .title("认证失败")
                .detail("认证失败，请重新登录")
                .status(HttpStatus.UNAUTHORIZED.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                AgentApiResponses.error(error, null, 0));
    }

    // ========== 兜底异常 ==========

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentApiResponse<Void>> handleGeneralException(
            Exception e, HttpServletRequest request) {
        log.error("[CONSUMER_099] 未处理异常 | path={} | type={} | msg={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);

        AgentError error = AgentError.builder()
                .code("CONSUMER_099")
                .title("服务内部异常")
                .detail("服务内部异常，请联系管理员")
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .instance(request.getRequestURI())
                .build();

        metricsCollector.recordError(error);
        return ResponseEntity.internalServerError().body(
                AgentApiResponses.error(error, null, 0));
    }

    // ========== 工具方法 ==========

    private String extractServiceName(String message) {
        if (message == null) return null;
        Pattern p = Pattern.compile("(?:for |uri=)(https?://[^/\"]+)");
        Matcher m = p.matcher(message);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
