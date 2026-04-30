package com.example.smartassistant.consumer.service.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus 自定义指标收集器
 * 用于监控 Consumer 端关键业务指标
 */
@Component
public class ConsumerMetricsCollector {

    // 计数器
    private final Counter totalRequestsCounter;
    private final Counter successfulRequestsCounter;
    private final Counter failedRequestsCounter;
    private final Counter jsonFormatRequestsCounter;
    private final Counter textFormatRequestsCounter;
    
    // 计时器
    private final Timer requestLatencyTimer;
    private final Timer promptBuildTimer;
    
    //  gauge（仪表盘）
    private final AtomicLong activeSessions = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong cacheMissCount = new AtomicLong(0);
    
    public ConsumerMetricsCollector(MeterRegistry meterRegistry) {

        // 初始化计数器
        this.totalRequestsCounter = Counter.builder("a2a_consumer_requests_total")
                .description("Total number of requests received by Consumer")
                .tag("service", "consumer-service")
                .register(meterRegistry);
        
        this.successfulRequestsCounter = Counter.builder("a2a_consumer_requests_success_total")
                .description("Total number of successful requests")
                .tag("service", "consumer-service")
                .register(meterRegistry);
        
        this.failedRequestsCounter = Counter.builder("a2a_consumer_requests_failed_total")
                .description("Total number of failed requests")
                .tag("service", "consumer-service")
                .register(meterRegistry);
        
        this.jsonFormatRequestsCounter = Counter.builder("a2a_consumer_json_format_requests_total")
                .description("Total number of requests using JSON format")
                .tag("service", "consumer-service")
                .register(meterRegistry);
        
        this.textFormatRequestsCounter = Counter.builder("a2a_consumer_text_format_requests_total")
                .description("Total number of requests using text format")
                .tag("service", "consumer-service")
                .register(meterRegistry);
        
        // 初始化计时器
        this.requestLatencyTimer = Timer.builder("a2a_consumer_request_latency")
                .description("Request latency in milliseconds")
                .tag("service", "consumer-service")
                .register(meterRegistry);
        
        this.promptBuildTimer = Timer.builder("a2a_consumer_prompt_build_time")
                .description("Time spent building prompts")
                .tag("service", "consumer-service")
                .register(meterRegistry);
        
        // 注册 Gauge
        Gauge.builder("a2a_consumer_active_sessions", activeSessions, AtomicLong::get)
                .description("Number of active sessions")
                .tag("service", "consumer-service")
                .register(meterRegistry);
        
        Gauge.builder("a2a_consumer_cache_hits", cacheHitCount, AtomicLong::get)
                .description("Cache hit count")
                .tag("service", "consumer-service")
                .register(meterRegistry);
        
        Gauge.builder("a2a_consumer_cache_misses", cacheMissCount, AtomicLong::get)
                .description("Cache miss count")
                .tag("service", "consumer-service")
                .register(meterRegistry);
    }
    
    /**
     * 记录请求总数
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
     * 记录 JSON 格式请求
     */
    public void recordJsonFormatRequest() {
        jsonFormatRequestsCounter.increment();
    }
    
    /**
     * 记录文本格式请求
     */
    public void recordTextFormatRequest() {
        textFormatRequestsCounter.increment();
    }
    
    /**
     * 记录请求延迟
     */
    public void recordRequestLatency(long milliseconds) {
        requestLatencyTimer.record(milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * 记录 Prompt 构建时间
     */
    public void recordPromptBuildTime(long milliseconds) {
        promptBuildTimer.record(milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * 更新活跃会话数
     */
    public void setActiveSessions(long count) {
        activeSessions.set(count);
    }
    
    /**
     * 记录缓存命中
     */
    public void recordCacheHit() {
        cacheHitCount.incrementAndGet();
    }
    
    /**
     * 记录缓存未命中
     */
    public void recordCacheMiss() {
        cacheMissCount.incrementAndGet();
    }
}
