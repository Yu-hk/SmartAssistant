package com.example.smartassistant.gateway.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Gateway 全局异常处理器（WebFlux 版本）
 * <p>
 * 错误码规范（GATEWAY_ 前缀）：
 * <ul>
 *   <li>GATEWAY_001: 参数校验失败</li>
 *   <li>GATEWAY_002: Token 无效或过期</li>
 *   <li>GATEWAY_003: 权限不足</li>
 *   <li>GATEWAY_004: 上游服务不可用</li>
 *   <li>GATEWAY_099: 未知错误</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ========== 参数校验 ==========

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[GATEWAY_001] 参数校验失败 | msg={}", e.getMessage());
        return ResponseEntity.badRequest().body(
                ErrorResponse.of("GATEWAY_001", e.getMessage(), HttpStatus.BAD_REQUEST.value()));
    }

    // ========== 认证异常 ==========

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurityException(SecurityException e) {
        log.warn("[GATEWAY_002] Token 无效 | msg={}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ErrorResponse.of("GATEWAY_002", "认证失败: " + e.getMessage(),
                        HttpStatus.UNAUTHORIZED.value()));
    }

    // ========== 上游服务异常 ==========

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ErrorResponse> handleWebClientException(WebClientResponseException e) {
        log.error("[GATEWAY_004] 上游服务返回错误 | status={}", e.getStatusCode().value());

        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        String code = "GATEWAY_004";
        String msg = "上游服务返回异常";

        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            code = "GATEWAY_002";
            msg = "上游服务认证失败";
        }

        return ResponseEntity.status(status).body(
                ErrorResponse.of(code, msg, status.value()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException e) {
        log.warn("[GATEWAY] ResponseStatusException | status={} | msg={}",
                e.getStatusCode().value(), e.getMessage());

        return ResponseEntity.status(e.getStatusCode()).body(
                ErrorResponse.of("GATEWAY_" + e.getStatusCode().value(),
                        e.getReason(), e.getStatusCode().value()));
    }

    // ========== 兜底异常 ==========

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception e) {
        log.error("[GATEWAY_099] 未处理异常 | type={} | msg={}",
                e.getClass().getSimpleName(), e.getMessage(), e);

        return ResponseEntity.internalServerError().body(
                ErrorResponse.of("GATEWAY_099", "网关内部异常",
                        HttpStatus.INTERNAL_SERVER_ERROR.value()));
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
        private String service = "gateway";
        @lombok.Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();

        public static ErrorResponse of(String code, String msg, int status) {
            return ErrorResponse.builder()
                    .code(code)
                    .msg(msg)
                    .status(status)
                    .service("gateway")
                    .timestamp(LocalDateTime.now())
                    .build();
        }
    }
}
