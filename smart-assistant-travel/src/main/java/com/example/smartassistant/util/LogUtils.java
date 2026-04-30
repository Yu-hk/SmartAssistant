package com.example.smartassistant.util;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * 统一日志工具类
 * 提供结构化的日志记录方法，便于 Loki 查询和分析
 */
@Slf4j
public class LogUtils {

    public static void logBusinessStart(String operation, Object... params) {
        MDC.put("operation", operation);
        log.info("[BUSINESS_START] {} | params={}", operation, params);
    }

    public static void logBusinessSuccess(String operation, long durationMs, Object result) {
        MDC.put("operation", operation);
        log.info("[BUSINESS_SUCCESS] {} | duration={}ms | result={}", operation, durationMs, result);
    }

    public static void logBusinessError(String operation, long durationMs, String errorMsg) {
        MDC.put("operation", operation);
        log.error("[BUSINESS_ERROR] {} | duration={}ms | error={}", operation, durationMs, errorMsg);
    }

    public static void logPerformance(String methodName, long durationMs, String status) {
        MDC.put("method", methodName);
        if (durationMs > 1000) {
            log.warn("[SLOW_METHOD] {} | duration={}ms | status={}", methodName, durationMs, status);
        } else {
            log.debug("[METHOD_EXEC] {} | duration={}ms | status={}", methodName, durationMs, status);
        }
    }

    public static void logApiRequest(String method, String uri, String userId, String requestId) {
        MDC.put("requestId", requestId);
        MDC.put("userId", userId);
        log.info("[API_REQUEST] {} {} | userId={} | requestId={}", method, uri, userId, requestId);
    }

    public static void logApiResponse(String method, String uri, int statusCode, long durationMs, String requestId) {
        MDC.put("requestId", requestId);
        if (statusCode >= 500) {
            log.error("[API_ERROR] {} {} | status={} | duration={}ms | requestId={}", 
                    method, uri, statusCode, durationMs, requestId);
        } else if (statusCode >= 400) {
            log.warn("[API_CLIENT_ERROR] {} {} | status={} | duration={}ms | requestId={}", 
                    method, uri, statusCode, durationMs, requestId);
        } else {
            log.info("[API_RESPONSE] {} {} | status={} | duration={}ms | requestId={}", 
                    method, uri, statusCode, durationMs, requestId);
        }
    }

    public static void logSlowQuery(String sql, long durationMs, int rowCount) {
        log.warn("[SLOW_QUERY] duration={}ms | rows={} | sql={}", durationMs, rowCount, sql);
    }

    public static void logExternalCall(String serviceName, String endpoint, long durationMs, int statusCode) {
        if (statusCode >= 500 || durationMs > 3000) {
            log.error("[EXTERNAL_CALL_FAIL] service={} | endpoint={} | status={} | duration={}ms", 
                    serviceName, endpoint, statusCode, durationMs);
        } else {
            log.info("[EXTERNAL_CALL] service={} | endpoint={} | status={} | duration={}ms", 
                    serviceName, endpoint, statusCode, durationMs);
        }
    }

    public static void logLoginAttempt(String username, String ip, boolean success) {
        if (success) {
            log.info("[LOGIN_SUCCESS] username={} | ip={}", username, ip);
        } else {
            log.warn("[LOGIN_FAILED] username={} | ip={}", username, ip);
        }
    }

    public static void logAccessDenied(String userId, String resource, String action) {
        log.warn("[ACCESS_DENIED] userId={} | resource={} | action={}", userId, resource, action);
    }

    public static void logAsyncTask(String taskName, String status, long durationMs) {
        log.info("[ASYNC_TASK] name={} | status={} | duration={}ms", taskName, status, durationMs);
    }
}
