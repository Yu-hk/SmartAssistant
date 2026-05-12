/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.exception;

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

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // ========== 参数校验异常 ==========

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");

        log.warn("[CONSUMER_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);

        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "CONSUMER_001", msg, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBindException(
            BindException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数绑定失败");

        log.warn("[CONSUMER_001] 参数绑定失败 | path={}", request.getRequestURI());

        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "CONSUMER_001", msg, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[CONSUMER_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "CONSUMER_001", e.getMessage(), HttpStatus.BAD_REQUEST.value()));
    }

    // ========== 远程服务调用异常 ==========

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ErrorResponse> handleHttpServerError(
            HttpServerErrorException e, HttpServletRequest request) {
        log.error("[CONSUMER_002] 远程服务错误 | path={} | status={}",
                request.getRequestURI(), e.getStatusCode().value());

        String detail = extractDetail(e.getMessage());
        ErrorResponse resp = ErrorResponse.of("CONSUMER_002", "上游服务异常，请稍后重试",
                HttpStatus.BAD_GATEWAY.value());
        resp.setDetail(detail);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(resp);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleResourceAccess(
            ResourceAccessException e, HttpServletRequest request) {
        log.error("[CONSUMER_002] 连接远程服务失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        String targetService = extractServiceName(e.getMessage());
        String msg = targetService != null
                ? "服务暂时不可用（" + targetService + "），请稍后重试"
                : "服务暂时不可用，请稍后重试";

        ErrorResponse resp = ErrorResponse.of("CONSUMER_002", msg,
                HttpStatus.BAD_GATEWAY.value());
        resp.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(resp);
    }

    // ========== 认证异常 ==========

    @ExceptionHandler({SecurityException.class})
    public ResponseEntity<ErrorResponse> handleSecurityException(
            SecurityException e, HttpServletRequest request) {
        log.warn("[CONSUMER_005] 认证失败 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ErrorResponse.of("CONSUMER_005", "认证失败，请重新登录",
                        HttpStatus.UNAUTHORIZED.value()));
    }

    // ========== 兜底异常 ==========

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(
            Exception e, HttpServletRequest request) {
        log.error("[CONSUMER_099] 未处理异常 | path={} | type={} | msg={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);

        ErrorResponse resp = ErrorResponse.of("CONSUMER_099", "服务内部异常，请联系管理员",
                HttpStatus.INTERNAL_SERVER_ERROR.value());
        resp.setDetail(e.getClass().getSimpleName() + ": " + e.getMessage());
        return ResponseEntity.internalServerError().body(resp);
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

    private String extractDetail(String message) {
        if (message == null) return null;
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    // ========== 统一响应模型 ==========

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class ErrorResponse {
        private String code;
        private String msg;
        private int status;
        @lombok.Builder.Default
        private String service = "consumer-service";
        @lombok.Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();
        private String traceId;
        private String detail;

        public static ErrorResponse of(String code, String msg, int status) {
            return ErrorResponse.builder()
                    .code(code)
                    .msg(msg)
                    .status(status)
                    .service("consumer-service")
                    .timestamp(LocalDateTime.now())
                    .build();
        }
    }
}
