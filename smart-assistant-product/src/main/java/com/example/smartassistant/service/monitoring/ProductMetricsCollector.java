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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Product Service 监控指标收集器
 * <p>
 * 采集业务级别指标（请求数/成功率/延迟）和 Agent 级别指标
 * （LLM Token 消耗、迭代、推理耗时），
 * 实现 {@link AgentMetricsCollector} 接口供 {@link SmartReActAgent} 自动调用。
 * </p>
 */
@Component
public class ProductMetricsCollector implements AgentMetricsCollector {

    private final MeterRegistry meterRegistry;

    // ========== 业务指标 ==========
    private final Counter totalRequestsCounter;
    private final Counter successfulRequestsCounter;
    private final Counter failedRequestsCounter;
    private final Timer requestLatencyTimer;
    private final Timer recommendationLatencyTimer;
    private final AtomicLong activeRecommendations = new AtomicLong(0);

    // ========== Agent / LLM 指标 ==========
    private final Counter tokenInputCounter;
    private final Counter tokenOutputCounter;
    private final Counter iterationCounter;
    private final Counter timeoutCounter;
    private final Counter maxIterationHitCounter;
    private final Counter contextCompressionCounter;
    private final Counter toolHallucinationCounter;
    private final Timer inferenceLatencyTimer;

    public ProductMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // ----- 业务计数器 -----
        this.totalRequestsCounter = Counter.builder("a2a_food_requests_total")
                .description("Total number of requests received by Food Service")
                .tag("service", "food-service")
                .register(meterRegistry);

        this.successfulRequestsCounter = Counter.builder("a2a_food_requests_success_total")
                .description("Total number of successful requests")
                .tag("service", "food-service")
                .register(meterRegistry);

        this.failedRequestsCounter = Counter.builder("a2a_food_requests_failed_total")
                .description("Total number of failed requests")
                .tag("service", "food-service")
                .register(meterRegistry);

        // ----- 业务计时器 -----
        this.requestLatencyTimer = Timer.builder("a2a_food_request_latency")
                .description("Request latency in milliseconds")
                .tag("service", "food-service")
                .register(meterRegistry);

        this.recommendationLatencyTimer = Timer.builder("a2a_food_recommendation_latency")
                .description("Recommendation generation latency in milliseconds")
                .tag("service", "food-service")
                .register(meterRegistry);

        // ----- Gauge -----
        meterRegistry.gauge("a2a_food_active_recommendations",
                activeRecommendations);

        // ----- Agent / LLM 指标 -----
        this.tokenInputCounter = Counter.builder("a2a_llm_token_input_total")
                .description("Total LLM input tokens")
                .tag("service", "product-service")
                .register(meterRegistry);

        this.tokenOutputCounter = Counter.builder("a2a_llm_token_output_total")
                .description("Total LLM output tokens")
                .tag("service", "product-service")
                .register(meterRegistry);

        this.iterationCounter = Counter.builder("a2a_agent_iteration_total")
                .description("Total agent iterations")
                .tag("service", "product-service")
                .register(meterRegistry);

        this.timeoutCounter = Counter.builder("a2a_agent_timeout_total")
                .description("Agent timeout count")
                .tag("service", "product-service")
                .register(meterRegistry);

        this.maxIterationHitCounter = Counter.builder("a2a_agent_max_iteration_hit_total")
                .description("Max iteration limit reached count")
                .tag("service", "product-service")
                .register(meterRegistry);

        this.contextCompressionCounter = Counter.builder("a2a_agent_context_compress_total")
                .description("Context compression trigger count")
                .tag("service", "product-service")
                .register(meterRegistry);

        this.toolHallucinationCounter = Counter.builder("a2a_agent_tool_hallucination_total")
                .description("Tool hallucination (unknown tool) count")
                .tag("service", "product-service")
                .register(meterRegistry);

        this.inferenceLatencyTimer = Timer.builder("a2a_llm_inference_latency")
                .description("LLM inference latency")
                .tag("service", "product-service")
                .register(meterRegistry);
    }

    // ==================== 业务指标 ====================

    public void recordTotalRequest() {
        totalRequestsCounter.increment();
    }

    public void recordSuccess() {
        successfulRequestsCounter.increment();
    }

    public void recordFailure() {
        failedRequestsCounter.increment();
    }

    public void recordRequestLatency(long milliseconds) {
        requestLatencyTimer.record(milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordRecommendationLatency(long milliseconds) {
        recommendationLatencyTimer.record(milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void incrementActiveRecommendations() {
        activeRecommendations.incrementAndGet();
    }

    public void decrementActiveRecommendations() {
        activeRecommendations.decrementAndGet();
    }

    // ==================== AgentMetricsCollector 实现 ====================

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
