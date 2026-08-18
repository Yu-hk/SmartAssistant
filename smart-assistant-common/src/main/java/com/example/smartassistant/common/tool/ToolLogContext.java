/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import org.slf4j.MDC;

/**
 * Tool 日志上下文 —— 在 ThreadLocal + MDC 中持有 requestId，
 * 供 {@link ToolLogAspect} 切面读取后写入 @Tool 方法日志。
 *
 * <p>使用方式：</p>
 * <pre>
 *   // 入口处设置
 *   ToolLogContext.setRequestId("abc123");
 *   try {
 *       // 业务逻辑（含 @Tool 方法调用）
 *   } finally {
 *       ToolLogContext.clear();
 *   }
 * </pre>
 *
 * @author SmartAssistant
 * @since 2026-05-18
 */
public final class ToolLogContext {

    /** MDC key，与 logback 配合使用 */
    public static final String MDC_KEY = "toolRequestId";

    private static final ThreadLocal<String> REQUEST_ID = new InheritableThreadLocal<>();
    private static final ThreadLocal<String> IDEMPOTENCY_KEY = new InheritableThreadLocal<>();

    /** Prevents the aspect and AgentToolExecutor from auditing one call twice. */
    private static final ThreadLocal<Integer> EXECUTOR_MANAGED_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private ToolLogContext() {}

    /**
     * 设置当前 requestId，同步写入 ThreadLocal 和 MDC。
     */
    public static void setRequestId(String requestId) {
        REQUEST_ID.set(requestId);
        if (requestId != null) {
            MDC.put(MDC_KEY, requestId);
        }
    }

    /**
     * 获取当前 requestId。
     */
    public static String getRequestId() {
        String id = REQUEST_ID.get();
        if (id == null) {
            id = MDC.get(MDC_KEY);
        }
        if (id == null) {
            id = MDC.get("requestId");
        }
        return id;
    }

    /** Sets the workflow write idempotency key propagated by the Router. */
    public static void setIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            IDEMPOTENCY_KEY.remove();
        } else {
            IDEMPOTENCY_KEY.set(idempotencyKey);
        }
    }

    public static String getIdempotencyKey() {
        return IDEMPOTENCY_KEY.get();
    }

    public static void enterExecutorManagedCall() {
        EXECUTOR_MANAGED_DEPTH.set(EXECUTOR_MANAGED_DEPTH.get() + 1);
    }

    public static void exitExecutorManagedCall() {
        int remaining = EXECUTOR_MANAGED_DEPTH.get() - 1;
        if (remaining <= 0) {
            EXECUTOR_MANAGED_DEPTH.remove();
        } else {
            EXECUTOR_MANAGED_DEPTH.set(remaining);
        }
    }

    public static boolean isExecutorManagedCall() {
        return EXECUTOR_MANAGED_DEPTH.get() > 0;
    }

    /**
     * 清除当前线程的 requestId（ThreadLocal + MDC）。
     */
    public static void clear() {
        REQUEST_ID.remove();
        IDEMPOTENCY_KEY.remove();
        EXECUTOR_MANAGED_DEPTH.remove();
        MDC.remove(MDC_KEY);
    }
}
