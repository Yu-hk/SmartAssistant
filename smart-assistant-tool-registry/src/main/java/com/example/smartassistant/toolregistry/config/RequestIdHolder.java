package com.example.smartassistant.toolregistry.config;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 请求 ID 上下文持有者。
 * <p>
 * 基于 {@link ThreadLocal} 存储当前请求的 trace ID，
 * 同时注入 SLF4J {@link MDC} 以便日志输出自动携带。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
public final class RequestIdHolder {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private RequestIdHolder() {}

    /**
     * 设置请求 ID。如果传入 null，自动生成 UUID。
     */
    public static String set(String requestId) {
        String id = (requestId != null && !requestId.isBlank()) ? requestId : generateId();
        HOLDER.set(id);
        MDC.put(MDC_KEY, id);
        return id;
    }

    /**
     * 获取当前请求 ID。
     */
    public static String get() {
        String id = HOLDER.get();
        if (id == null) {
            id = generateId();
            HOLDER.set(id);
            MDC.put(MDC_KEY, id);
        }
        return id;
    }

    /**
     * 清除上下文（Filter 中 finally 调用）。
     */
    public static void clear() {
        HOLDER.remove();
        MDC.remove(MDC_KEY);
    }

    private static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
