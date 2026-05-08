package com.example.smartassistant.general.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("[GENERAL_001] 参数校验失败 | path={} | msg={}", request.getRequestURI(), msg);
        return ResponseEntity.badRequest().body(ErrorResponse.of("GENERAL_001", msg, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[GENERAL_001] 非法参数 | path={} | msg={}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of("GENERAL_001", e.getMessage(), HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(
            Exception e, HttpServletRequest request) {
        log.error("[GENERAL_099] 未知错误 | path={} | msg={}", request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("GENERAL_099", "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    public record ErrorResponse(String code, String message, int status, String timestamp) {
        static ErrorResponse of(String code, String message, int status) {
            return new ErrorResponse(code, message, status, LocalDateTime.now().toString());
        }
    }
}
