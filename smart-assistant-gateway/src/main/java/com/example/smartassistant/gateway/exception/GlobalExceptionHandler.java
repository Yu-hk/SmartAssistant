package com.example.smartassistant.gateway.exception;

import com.example.smartassistant.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gateway 全局异常处理器（WebFlux 版本）
 * <p>
 * 统一使用 {@link ApiResponse} 格式返回错误响应。
 * 注意：WebFlux 环境下不使用 HttpServletRequest。
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
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[GATEWAY_001] 参数校验失败 | msg={}", e.getMessage());
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "GATEWAY_001", e.getMessage(), null, null);
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), e.getMessage(), error));
    }

    // ========== 认证异常 ==========

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(SecurityException e) {
        log.warn("[GATEWAY_002] Token 无效 | msg={}", e.getMessage());
        String msg = "认证失败: " + e.getMessage();
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "GATEWAY_002", msg, null, null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), msg, error));
    }

    // ========== 上游服务异常 ==========

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleWebClientException(WebClientResponseException e) {
        log.error("[GATEWAY_004] 上游服务返回错误 | status={}", e.getStatusCode().value());

        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        String code = "GATEWAY_004";
        String msg = "上游服务返回异常";

        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            code = "GATEWAY_002";
            msg = "上游服务认证失败";
        }

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                code, e.getResponseBodyAsString(), null, null);
        return ResponseEntity.status(status).body(
                ApiResponse.error(status.value(), msg, error));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException e) {
        log.warn("[GATEWAY] ResponseStatusException | status={} | msg={}",
                e.getStatusCode().value(), e.getMessage());

        String code = "GATEWAY_" + e.getStatusCode().value();
        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                code, e.getReason(), null, null);
        return ResponseEntity.status(e.getStatusCode()).body(
                ApiResponse.error(e.getStatusCode().value(), e.getReason(), error));
    }

    // ========== 兜底异常 ==========

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception e) {
        log.error("[GATEWAY_099] 未处理异常 | type={} | msg={}",
                e.getClass().getSimpleName(), e.getMessage(), e);

        ApiResponse.ErrorDetail error = new ApiResponse.ErrorDetail(
                "GATEWAY_099", e.getClass().getSimpleName() + ": " + e.getMessage(), null, null);
        return ResponseEntity.internalServerError().body(
                ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "网关内部异常", error));
    }
}
