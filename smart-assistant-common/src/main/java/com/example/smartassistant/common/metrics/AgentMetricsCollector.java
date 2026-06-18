/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.metrics;

/**
 * Agent 指标采集回调接口。
 * <p>
 * 由 {@code SmartReActAgent} 在关键路径上调用，各模块（Router/Order/Product）
 * 提供 Micrometer 实现，记录 Prometheus 指标。
 * 所有方法都有默认空实现，不强制要求实现。
 * </p>
 */
public interface AgentMetricsCollector {

    /** LLM Token 消耗 */
    default void recordTokenUsage(int inputTokens, int outputTokens) {}

    /** Agent 迭代轮数（一次 execute 中的循环次数） */
    default void recordIteration(int iterationCount) {}

    /** 单次 LLM 推理耗时 */
    default void recordInferenceLatency(long millis) {}

    /** 工具调用记录 */
    default void recordToolCall(String toolName, long durationMs, boolean success) {}

    /** 上下文压缩触发 */
    default void recordContextCompression() {}

    /** 工具幻觉（LLM 调用了不存在的工具） */
    default void recordToolHallucination() {}

    /** Agent 执行超时 */
    default void recordTimeout() {}

    /** 达到最大迭代次数上限 */
    default void recordMaxIterationHit() {}

    /** 并行工具执行 */
    default void recordParallelToolExecution(int count) {}
}
