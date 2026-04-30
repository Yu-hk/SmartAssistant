package com.example.smartassistant.consumer.util;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * 统一日志工具类
 * 提供结构化的日志记录方法，便于 Loki 查询和分析
 */
@Slf4j
public class LogUtils {

    // ==================== 业务操作日志 ====================

    /**
     * 记录业务操作开始
     */
    public static void logBusinessStart(String operation, Object... params) {
        MDC.put("operation", operation);
        log.info("[BUSINESS_START] {} | params={}", operation, params);
    }

    /**
     * 记录业务操作成功
     */
    public static void logBusinessSuccess(String operation, long durationMs, Object result) {
        MDC.put("operation", operation);
        log.info("[BUSINESS_SUCCESS] {} | duration={}ms | result={}", operation, durationMs, result);
    }

    /**
     * 记录业务操作失败
     */
    public static void logBusinessError(String operation, long durationMs, String errorMsg) {
        MDC.put("operation", operation);
        log.error("[BUSINESS_ERROR] {} | duration={}ms | error={}", operation, durationMs, errorMsg);
    }

    // ==================== 性能监控日志 ====================

    /**
     * 记录方法执行时间
     */
    public static void logPerformance(String methodName, long durationMs, String status) {
        MDC.put("method", methodName);
        if (durationMs > 1000) {
            log.warn("[SLOW_METHOD] {} | duration={}ms | status={}", methodName, durationMs, status);
        } else {
            log.debug("[METHOD_EXEC] {} | duration={}ms | status={}", methodName, durationMs, status);
        }
    }

    // ==================== API 请求日志 ====================

    /**
     * 记录 API 请求
     */
    public static void logApiRequest(String method, String uri, String userId, String requestId) {
        MDC.put("requestId", requestId);
        MDC.put("userId", userId);
        log.info("[API_REQUEST] {} {} | userId={} | requestId={}", method, uri, userId, requestId);
    }

    /**
     * 记录 API 响应
     */
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

    // ==================== 数据库操作日志 ====================

    /**
     * 记录慢查询
     */
    public static void logSlowQuery(String sql, long durationMs, int rowCount) {
        log.warn("[SLOW_QUERY] duration={}ms | rows={} | sql={}", durationMs, rowCount, sql);
    }

    // ==================== 外部调用日志 ====================

    /**
     * 记录外部 API 调用
     */
    public static void logExternalCall(String serviceName, String endpoint, long durationMs, int statusCode) {
        if (statusCode >= 500 || durationMs > 3000) {
            log.error("[EXTERNAL_CALL_FAIL] service={} | endpoint={} | status={} | duration={}ms", 
                    serviceName, endpoint, statusCode, durationMs);
        } else {
            log.info("[EXTERNAL_CALL] service={} | endpoint={} | status={} | duration={}ms", 
                    serviceName, endpoint, statusCode, durationMs);
        }
    }

    // ==================== 安全相关日志 ====================

    /**
     * 记录登录尝试
     */
    public static void logLoginAttempt(String username, String ip, boolean success) {
        if (success) {
            log.info("[LOGIN_SUCCESS] username={} | ip={}", username, ip);
        } else {
            log.warn("[LOGIN_FAILED] username={} | ip={}", username, ip);
        }
    }

    /**
     * 记录权限拒绝
     */
    public static void logAccessDenied(String userId, String resource, String action) {
        log.warn("[ACCESS_DENIED] userId={} | resource={} | action={}", userId, resource, action);
    }

    // ==================== 异步任务日志 ====================

    /**
     * 记录异步任务
     */
    public static void logAsyncTask(String taskName, String status, long durationMs) {
        log.info("[ASYNC_TASK] name={} | status={} | duration={}ms", taskName, status, durationMs);
    }
}
