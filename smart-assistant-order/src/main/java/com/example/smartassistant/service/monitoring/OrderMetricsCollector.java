/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.monitoring;

import com.example.smartassistant.common.metrics.AgentMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Order Service Agent 指标收集器
 * <p>
 * 采集 LLM Token 消耗、Agent 迭代、推理耗时等指标，
 * 实现 {@link AgentMetricsCollector} 接口，
 * 由 {@link SmartReActAgent} 在运行周期中自动调用。
 * </p>
 */
@Component
public class OrderMetricsCollector implements AgentMetricsCollector {

    private final Counter tokenInputCounter;
    private final Counter tokenOutputCounter;
    private final Counter iterationCounter;
    private final Counter contextCompressionCounter;
    private final Counter toolHallucinationCounter;
    private final Counter timeoutCounter;
    private final Counter maxIterationHitCounter;
    private final Timer inferenceLatencyTimer;

    public OrderMetricsCollector(MeterRegistry meterRegistry) {
        this.tokenInputCounter = Counter.builder("a2a_llm_token_input_total")
                .description("Total LLM input tokens")
                .tag("service", "order-service")
                .register(meterRegistry);

        this.tokenOutputCounter = Counter.builder("a2a_llm_token_output_total")
                .description("Total LLM output tokens")
                .tag("service", "order-service")
                .register(meterRegistry);

        this.iterationCounter = Counter.builder("a2a_agent_iteration_total")
                .description("Total agent iterations")
                .tag("service", "order-service")
                .register(meterRegistry);

        this.timeoutCounter = Counter.builder("a2a_agent_timeout_total")
                .description("Agent timeout count")
                .tag("service", "order-service")
                .register(meterRegistry);

        this.maxIterationHitCounter = Counter.builder("a2a_agent_max_iteration_hit_total")
                .description("Max iteration limit reached count")
                .tag("service", "order-service")
                .register(meterRegistry);

        this.contextCompressionCounter = Counter.builder("a2a_agent_context_compress_total")
                .description("Context compression trigger count")
                .tag("service", "order-service")
                .register(meterRegistry);

        this.toolHallucinationCounter = Counter.builder("a2a_agent_tool_hallucination_total")
                .description("Tool hallucination (unknown tool) count")
                .tag("service", "order-service")
                .register(meterRegistry);

        this.inferenceLatencyTimer = Timer.builder("a2a_llm_inference_latency")
                .description("LLM inference latency")
                .tag("service", "order-service")
                .register(meterRegistry);
    }

    @Override
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenInputCounter.increment(inputTokens);
        tokenOutputCounter.increment(outputTokens);
    }

    @Override
    public void recordIteration(int iterationCount) {
        iterationCounter.increment();
    }

    @Override
    public void recordInferenceLatency(long millis) {
        inferenceLatencyTimer.record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordContextCompression() {
        contextCompressionCounter.increment();
    }

    @Override
    public void recordToolHallucination() {
        toolHallucinationCounter.increment();
    }

    @Override
    public void recordTimeout() {
        timeoutCounter.increment();
    }

    @Override
    public void recordMaxIterationHit() {
        maxIterationHitCounter.increment();
    }
}
