/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Food Service 监控指标收集器
 * 记录请求延迟、成功率等关键指标
 */
@Component
public class ProductMetricsCollector {
    
    private final MeterRegistry meterRegistry;
    
    // 计数器
    private final Counter totalRequestsCounter;
    private final Counter successfulRequestsCounter;
    private final Counter failedRequestsCounter;
    
    // 计时器
    private final Timer requestLatencyTimer;
    private final Timer recommendationLatencyTimer;
    
    // Gauge（仪表盘）
    private final AtomicLong activeRecommendations = new AtomicLong(0);
    
    public ProductMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 初始化计数器
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
        
        // 初始化计时器
        this.requestLatencyTimer = Timer.builder("a2a_food_request_latency")
                .description("Request latency in milliseconds")
                .tag("service", "food-service")
                .register(meterRegistry);
        
        this.recommendationLatencyTimer = Timer.builder("a2a_food_recommendation_latency")
                .description("Recommendation generation latency in milliseconds")
                .tag("service", "food-service")
                .register(meterRegistry);
        
        // 注册 Gauge
        meterRegistry.gauge("a2a_food_active_recommendations", 
                activeRecommendations);
    }
    
    /**
     * 记录总请求数
     */
    public void recordTotalRequest() {
        totalRequestsCounter.increment();
    }
    
    /**
     * 记录成功请求
     */
    public void recordSuccess() {
        successfulRequestsCounter.increment();
    }
    
    /**
     * 记录失败请求
     */
    public void recordFailure() {
        failedRequestsCounter.increment();
    }
    
    /**
     * 记录请求延迟（毫秒）
     */
    public void recordRequestLatency(long milliseconds) {
        requestLatencyTimer.record(milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * 记录推荐生成延迟（毫秒）
     */
    public void recordRecommendationLatency(long milliseconds) {
        recommendationLatencyTimer.record(milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * 增加活跃推荐数
     */
    public void incrementActiveRecommendations() {
        activeRecommendations.incrementAndGet();
    }
    
    /**
     * 减少活跃推荐数
     */
    public void decrementActiveRecommendations() {
        activeRecommendations.decrementAndGet();
    }
}
