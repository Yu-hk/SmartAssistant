/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

/**
 * User Service 全局异常处理器
 * <p>
 * 错误码规范（USER_ 前缀）：
 * <ul>
 *   <li>USER_001: 参数校验失败</li>
 *   <li>USER_002: 认证失败（用户名/密码错误/Token无效）</li>
 *   <li>USER_003: 用户不存在</li>
 *   <li>USER_004: 账户冲突（重复注册等）</li>
 *   <li>USER_099: 未知错误</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("[USER_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);
        return ResponseEntity.badRequest().body(ErrorResponse.of("USER_001", msg, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[USER_002] 请求参数错误 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of("USER_002", e.getMessage(), HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurity(
            SecurityException e, HttpServletRequest request) {
        log.warn("[USER_002] 认证失败 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("USER_002", "认证失败", HttpStatus.UNAUTHORIZED.value()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(
            Exception e, HttpServletRequest request) {
        log.error("[USER_099] 未知错误 | path={} | msg={}", request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("USER_099", "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    // ========== 内部类型 ==========

    public record ErrorResponse(String code, String message, int status, String timestamp) {
        static ErrorResponse of(String code, String message, int status) {
            return new ErrorResponse(code, message, status, LocalDateTime.now().toString());
        }
    }
}
