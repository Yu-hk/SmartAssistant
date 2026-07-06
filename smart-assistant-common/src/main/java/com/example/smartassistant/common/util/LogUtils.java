/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.util;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * 统一日志工具类（全项目共享）。
 *
 * <p>提供结构化的日志记录方法，便于 Loki 查询和分析。
 * 替代各模块重复的 LogUtils 实现。
 *
 * <p>使用方法：直接静态调用 {@code LogUtils.logXxx(...)}。
 */
@Slf4j
public final class LogUtils {

    private LogUtils() {
        // 工具类，禁止实例化
    }

    // ==================== 业务操作日志 ====================

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

    // ==================== 性能监控日志 ====================

    public static void logPerformance(String methodName, long durationMs, String status) {
        MDC.put("method", methodName);
        if (durationMs > 1000) {
            log.warn("[SLOW_METHOD] {} | duration={}ms | status={}", methodName, durationMs, status);
        } else {
            log.debug("[METHOD_EXEC] {} | duration={}ms | status={}", methodName, durationMs, status);
        }
    }

    // ==================== API 请求日志 ====================

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

    // ==================== 数据库操作日志 ====================

    public static void logSlowQuery(String sql, long durationMs, int rowCount) {
        log.warn("[SLOW_QUERY] duration={}ms | rows={} | sql={}", durationMs, rowCount, sql);
    }

    // ==================== 外部调用日志 ====================

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

    // ==================== 异步任务日志 ====================

    public static void logAsyncTask(String taskName, String status, long durationMs) {
        log.info("[ASYNC_TASK] name={} | status={} | duration={}ms", taskName, status, durationMs);
    }
}
