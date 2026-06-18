/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.monitoring;

import com.example.smartassistant.common.metrics.AgentMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus 自定义指标收集器（Router Service）
 * <p>
 * 实现 {@link AgentMetricsCollector} 以采集 SmartReActAgent 的 LLM/迭代指标。
 * </p>
 */
@Component
public class RouterMetricsCollector implements AgentMetricsCollector {

    private final Counter totalRoutingRequestsCounter;
    private final Counter successfulRoutingCounter;
    private final Counter failedRoutingCounter;
    private final Counter multiIntentRequestsCounter;
    private final Counter singleIntentRequestsCounter;
    
    private final Timer routingLatencyTimer;
    private final Timer keywordExtractionTimer;
    
    private final AtomicLong activeAgents = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);

    // ⭐ Agent/LLM 指标
    private final Counter tokenInputCounter;
    private final Counter tokenOutputCounter;
    private final Counter iterationCounter;
    private final Counter timeoutCounter;
    private final Counter maxIterationHitCounter;
    private final Counter contextCompressionCounter;
    private final Counter toolHallucinationCounter;
    private final Timer inferenceLatencyTimer;

    public RouterMetricsCollector(MeterRegistry meterRegistry) {

        this.totalRoutingRequestsCounter = Counter.builder("a2a_router_routing_requests_total")
                .description("Total number of routing requests")
                .tag("service", "router-service")
                .register(meterRegistry);
        
        this.successfulRoutingCounter = Counter.builder("a2a_router_routing_success_total")
                .description("Total number of successful routings")
                .tag("service", "router-service")
                .register(meterRegistry);
        
        this.failedRoutingCounter = Counter.builder("a2a_router_routing_failed_total")
                .description("Total number of failed routings")
                .tag("service", "router-service")
                .register(meterRegistry);
        
        this.multiIntentRequestsCounter = Counter.builder("a2a_router_multi_intent_requests_total")
                .description("Total number of multi-intent requests")
                .tag("service", "router-service")
                .register(meterRegistry);
        
        this.singleIntentRequestsCounter = Counter.builder("a2a_router_single_intent_requests_total")
                .description("Total number of single-intent requests")
                .tag("service", "router-service")
                .register(meterRegistry);
        
        this.routingLatencyTimer = Timer.builder("a2a_router_routing_latency")
                .description("Routing latency in milliseconds")
                .tag("service", "router-service")
                .register(meterRegistry);
        
        this.keywordExtractionTimer = Timer.builder("a2a_router_keyword_extraction_time")
                .description("Time spent on keyword extraction")
                .tag("service", "router-service")
                .register(meterRegistry);
        
        Gauge.builder("a2a_router_active_agents", activeAgents, AtomicLong::get)
                .description("Number of active agents discovered")
                .tag("service", "router-service")
                .register(meterRegistry);
        
        Gauge.builder("a2a_router_cache_hits", cacheHitCount, AtomicLong::get)
                .description("Keyword extraction cache hits")
                .tag("service", "router-service")
                .register(meterRegistry);

        // ⭐ Agent/LLM 指标
        this.tokenInputCounter = Counter.builder("a2a_llm_token_input_total")
                .description("Total LLM input tokens")
                .tag("service", "router-service")
                .register(meterRegistry);

        this.tokenOutputCounter = Counter.builder("a2a_llm_token_output_total")
                .description("Total LLM output tokens")
                .tag("service", "router-service")
                .register(meterRegistry);

        this.iterationCounter = Counter.builder("a2a_agent_iteration_total")
                .description("Total agent iterations")
                .tag("service", "router-service")
                .register(meterRegistry);

        this.timeoutCounter = Counter.builder("a2a_agent_timeout_total")
                .description("Agent timeout count")
                .tag("service", "router-service")
                .register(meterRegistry);

        this.maxIterationHitCounter = Counter.builder("a2a_agent_max_iteration_hit_total")
                .description("Max iteration limit reached count")
                .tag("service", "router-service")
                .register(meterRegistry);

        this.contextCompressionCounter = Counter.builder("a2a_agent_context_compress_total")
                .description("Context compression trigger count")
                .tag("service", "router-service")
                .register(meterRegistry);

        this.toolHallucinationCounter = Counter.builder("a2a_agent_tool_hallucination_total")
                .description("Tool hallucination (unknown tool) count")
                .tag("service", "router-service")
                .register(meterRegistry);

        this.inferenceLatencyTimer = Timer.builder("a2a_llm_inference_latency")
                .description("LLM inference latency")
                .tag("service", "router-service")
                .register(meterRegistry);
    }
    
    public void recordTotalRoutingRequest() {
        totalRoutingRequestsCounter.increment();
    }
    
    public void recordSuccess() {
        successfulRoutingCounter.increment();
    }
    
    public void recordFailure() {
        failedRoutingCounter.increment();
    }
    
    public void recordMultiIntentRequest() {
        multiIntentRequestsCounter.increment();
    }
    
    public void recordSingleIntentRequest() {
        singleIntentRequestsCounter.increment();
    }
    
    public void recordRoutingLatency(long milliseconds) {
        routingLatencyTimer.record(milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    public void recordKeywordExtractionTime(long milliseconds) {
        keywordExtractionTimer.record(milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    public void setActiveAgents(long count) {
        activeAgents.set(count);
    }
    
    public void recordCacheHit() {
        cacheHitCount.incrementAndGet();
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
