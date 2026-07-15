package com.example.smartassistant.common.exception;

import com.example.smartassistant.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一全局异常处理器（Servlet 容器版本）
 * <p>
 * 由各模块 {@code spring.application.name} 动态决定错误码前缀。
 * 示例：{@code spring.application.name=router} → {@code ROUTER_001} / {@code ROUTER_099}。
 * <p>
 * 使用 {@code @Order(HIGHEST_PRECEDENCE + 1)} 给模块层自定义处理器（如 Router 的
 * {@code RoutingException}）优先处理的机会。
 * <p>
 * 错误码规范：
 * <ul>
 *   <li>{PREFIX}_001: 参数校验失败</li>
 *   <li>{PREFIX}_002: 认证/鉴权失败</li>
 *   <li>{PREFIX}_004: 服务/数据访问异常</li>
 *   <li>{PREFIX}_099: 未知错误</li>
 * </ul>
 * 模块独有错误码（003/005 等）通过 {@link ServiceException} 携带。
 * <p>
 * 所有错误码格式化、错误明细构造、traceId 解析均委托
 * {@link ExceptionHandlerSupport}，与 router / gateway 保持一致，避免漂移。
 */
@Slf4j
@Order
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 模块标识（由 spring.application.name 决定错误码前缀） */
    @Value("${spring.application.name:APP}")
    private String moduleName;

    // ===================== 参数校验异常 =====================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");

        log.warn("[{}_001] 参数校验失败 | path={} | msg={}", moduleName, request.getRequestURI(), msg);

        return badRequest(ExceptionHandlerSupport.formatModuleCode(moduleName, "001"), msg, collectFieldErrors(e));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(
            BindException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数绑定失败");

        log.warn("[{}_001] 参数绑定失败 | path={}", moduleName, request.getRequestURI());

        return badRequest(ExceptionHandlerSupport.formatModuleCode(moduleName, "001"), msg, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("[{}_001] 非法参数 | path={} | msg={}", moduleName, request.getRequestURI(), e.getMessage());

        return badRequest(ExceptionHandlerSupport.formatModuleCode(moduleName, "001"), e.getMessage(), null);
    }

    // ===================== 认证异常 =====================

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(
            SecurityException e, HttpServletRequest request) {
        log.warn("[{}_002] 认证失败 | path={} | msg={}", moduleName, request.getRequestURI(), e.getMessage());

        ApiResponse.ErrorDetail error = ExceptionHandlerSupport.buildErrorDetail(
                ExceptionHandlerSupport.formatModuleCode(moduleName, "002"), "认证失败，请重新登录", null,
                ExceptionHandlerSupport.getTraceIdFromMdc());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "认证失败，请重新登录", error));
    }

    // ===================== ServiceException（业务异常，携带具体错误码）=====================

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleServiceException(
            ServiceException e, HttpServletRequest request) {
        log.warn("[SERVICE_EX] path={} | code={} | msg={}",
                request.getRequestURI(), e.getErrorCode(), e.getMessage());

        ApiResponse.ErrorDetail error = ExceptionHandlerSupport.buildErrorDetail(
                e.getErrorCode(), e.getDetail(), null, ExceptionHandlerSupport.getTraceIdFromMdc());
        return ResponseEntity.status(e.getHttpStatus()).body(
                ApiResponse.error(e.getHttpStatus(), e.getMessage(), error));
    }

    // ===================== 数据访问异常 =====================

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(
            DataAccessException e, HttpServletRequest request) {
        log.error("[{}_004] 数据访问异常 | path={} | msg={}",
                moduleName, request.getRequestURI(), e.getMessage());

        ApiResponse.ErrorDetail error = ExceptionHandlerSupport.buildErrorDetail(
                ExceptionHandlerSupport.formatModuleCode(moduleName, "004"),
                ExceptionHandlerSupport.truncate(e.getMessage(), 200), null,
                ExceptionHandlerSupport.getTraceIdFromMdc());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "数据库暂时不可用，请稍后重试", error));
    }

    // ===================== 远程服务异常 =====================

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceAccessException(
            ResourceAccessException e, HttpServletRequest request) {
        log.error("[{}_004] 远程服务连接失败 | path={} | msg={}",
                moduleName, request.getRequestURI(), e.getMessage());

        String targetService = extractServiceName(e.getMessage());
        String userMsg = targetService != null
                ? "服务暂时不可用（" + targetService + "），请稍后重试"
                : "服务暂时不可用，请稍后重试";

        ApiResponse.ErrorDetail error = ExceptionHandlerSupport.buildErrorDetail(
                ExceptionHandlerSupport.formatModuleCode(moduleName, "004"), e.getMessage(), null,
                ExceptionHandlerSupport.getTraceIdFromMdc());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ApiResponse.error(HttpStatus.BAD_GATEWAY.value(), userMsg, error));
    }

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpServerErrorException(
            HttpServerErrorException e, HttpServletRequest request) {
        log.error("[{}_004] 远程服务返回错误 | path={} | status={}",
                moduleName, request.getRequestURI(), e.getStatusCode().value());

        ApiResponse.ErrorDetail error = ExceptionHandlerSupport.buildErrorDetail(
                ExceptionHandlerSupport.formatModuleCode(moduleName, "004"),
                ExceptionHandlerSupport.truncate(e.getMessage(), 200), null,
                ExceptionHandlerSupport.getTraceIdFromMdc());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ApiResponse.error(HttpStatus.BAD_GATEWAY.value(), "上游服务异常，请稍后重试", error));
    }

    // ===================== 兜底异常 =====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(
            Exception e, HttpServletRequest request) {
        log.error("[{}_099] 未处理异常 | path={} | type={} | msg={}",
                moduleName, request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);

        ApiResponse.ErrorDetail error = ExceptionHandlerSupport.buildErrorDetail(
                ExceptionHandlerSupport.formatModuleCode(moduleName, "099"),
                e.getClass().getSimpleName() + ": " + e.getMessage(), null,
                ExceptionHandlerSupport.getTraceIdFromMdc());
        return ResponseEntity.internalServerError().body(
                ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部异常，请联系管理员", error));
    }

    // ===================== 工具方法 =====================

    /** 快速创建 400 响应 */
    private ResponseEntity<ApiResponse<Void>> badRequest(String code, String msg, Map<String, String> fieldErrors) {
        ApiResponse.ErrorDetail error = ExceptionHandlerSupport.buildErrorDetail(
                code, msg, fieldErrors, ExceptionHandlerSupport.getTraceIdFromMdc());
        return ResponseEntity.badRequest().body(
                ApiResponse.error(HttpStatus.BAD_REQUEST.value(), msg, error));
    }

    private String extractServiceName(String message) {
        if (message == null) return null;
        Pattern p = Pattern.compile("(?:for |uri=)(https?://[^/\"]+)");
        Matcher m = p.matcher(message);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private Map<String, String> collectFieldErrors(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(err ->
                fieldErrors.put(err.getField(), err.getDefaultMessage()));
        return fieldErrors.isEmpty() ? null : fieldErrors;
    }
}
