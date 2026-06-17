/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import org.slf4j.MDC;

/**
 * Tool 日志上下文 —— 在 MDC 中持有 requestId，
 * 供 {@link ToolLogAspect} 切面读取后写入 @Tool 方法日志。
 *
 * <p>使用 MDC 替代 ThreadLocal，确保：
 * <ul>
 *   <li>与 Spring Boot 虚拟线程上下文传播兼容（SB 3.4+ 自动处理 MDC 传播）</li>
 *   <li>避免 ThreadLocal 在虚拟线程回收时数据污染</li>
 *   <li>@Async 场景下 MDC 自动继承到子线程</li>
 * </ul>
 * </p>
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

    private ToolLogContext() {}

    /**
     * 设置当前 requestId，写入 MDC。
     */
    public static void setRequestId(String requestId) {
        if (requestId != null) {
            MDC.put(MDC_KEY, requestId);
        } else {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 获取当前 requestId。
     */
    public static String getRequestId() {
        return MDC.get(MDC_KEY);
    }

    /**
     * 清除当前上下文的 requestId（MDC）。
     */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
