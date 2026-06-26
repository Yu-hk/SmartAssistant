/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.trace;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 追踪跨度工具 —— 为关键操作创建 Micrometer Observation 嵌套跨度。
 *
 * <p>与现有的 {@code management.tracing.sampling.probability: 0.3} 配合使用，
 * 在 Jaeger/Zipkin UI 中呈现 Agent 执行过程的完整调用链。</p>
 *
 * <p>典型跨度层级：</p>
 * <pre>
 * Consumer request
 *   └─ Router: route
 *        └─ Agent: execute
 *             ├─ llm-call      (SmartReActAgent.think)
 *             ├─ tool-execute  (ToolLogAspect / executeToolCallWithRetry)
 *             └─ llm-call      (SmartReActAgent.summarize)
 * </pre>
 *
 * <p>使用方式（注入 ObservationRegistry）：</p>
 * <pre>
 * TraceSpan.of(registry, "llm-call").tag("model", "deepseek-r1").run(() -> chatModel.call(prompt));
 * </pre>
 */
public class TraceSpan {

    private static final Logger log = LoggerFactory.getLogger(TraceSpan.class);

    private final ObservationRegistry registry;
    private final String spanName;

    private TraceSpan(ObservationRegistry registry, String spanName) {
        this.registry = registry != null ? registry : ObservationRegistry.NOOP;
        this.spanName = spanName;
    }

    /**
     * 创建一个新的追踪跨度构建器。
     *
     * @param registry ObservationRegistry（可能为 null，null 时跨度不产生任何操作）
     * @param name     跨度名称，如 {@code "llm-call"}、{@code "tool-query-order"}
     */
    public static TraceSpan of(ObservationRegistry registry, String name) {
        return new TraceSpan(registry, name);
    }

    /**
     * 在当前跨度内执行操作。
     */
    public <T> T run(Supplier<T> action) {
        Observation observation = Observation.createNotStarted(spanName, registry);
        try {
            return observation.observe(action);
        } catch (Exception e) {
            observation.error(e);
            throw e;
        }
    }

    /**
     * 在当前跨度内执行无返回值的操作。
     */
    public void run(Runnable action) {
        Observation observation = Observation.createNotStarted(spanName, registry);
        try {
            observation.observe(action);
        } catch (Exception e) {
            observation.error(e);
            throw e;
        }
    }
}
