/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

/**
 * Food Agent 全局异常处理器
 * <p>
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
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");

        log.warn("[FOOD_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);

        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "FOOD_001", msg, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[FOOD_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "FOOD_001", e.getMessage(), HttpStatus.BAD_REQUEST.value()));
    }

    // ========== 数据访问异常 ==========

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccessException(
            org.springframework.dao.DataAccessException e, HttpServletRequest request) {
        log.error("[FOOD_004] 数据访问异常 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ErrorResponse.of("FOOD_004", "数据库暂时不可用，请稍后重试",
                        HttpStatus.SERVICE_UNAVAILABLE.value())
                        .withDetail(truncate(e.getMessage(), 200)));
    }

    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleResourceAccessException(
            org.springframework.web.client.ResourceAccessException e, HttpServletRequest request) {
        log.error("[FOOD_004] 远程资源访问失败 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ErrorResponse.of("FOOD_004", "外部服务暂时不可用",
                        HttpStatus.BAD_GATEWAY.value())
                        .withDetail(e.getMessage()));
    }

    // ========== 权限异常 ==========

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurityException(
            SecurityException e, HttpServletRequest request) {
        log.warn("[FOOD_005] 权限不足 | path={} | msg={}",
                request.getRequestURI(), e.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ErrorResponse.of("FOOD_005", "权限不足: " + e.getMessage(),
                        HttpStatus.FORBIDDEN.value()));
    }

    // ========== 兜底异常 ==========

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(
            Exception e, HttpServletRequest request) {
        log.error("[FOOD_099] 未处理异常 | path={} | type={} | msg={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);

        return ResponseEntity.internalServerError().body(
                ErrorResponse.of("FOOD_099", "服务内部异常，请联系管理员",
                        HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .withDetail(e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    // ========== 工具方法 ==========

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
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
        private String service = "food-agent";
        @lombok.Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();
        private String detail;

        public static ErrorResponse of(String code, String msg, int status) {
            return ErrorResponse.builder()
                    .code(code)
                    .msg(msg)
                    .status(status)
                    .service("food-agent")
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        public ErrorResponse withDetail(String detail) {
            this.detail = detail;
            return this;
        }
    }
}
