package com.example.smartassistant.router.service.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus 自定义指标收集器（Router Service）
 */
@Component
public class RouterMetricsCollector {

    private final Counter totalRoutingRequestsCounter;
    private final Counter successfulRoutingCounter;
    private final Counter failedRoutingCounter;
    private final Counter multiIntentRequestsCounter;
    private final Counter singleIntentRequestsCounter;
    
    private final Timer routingLatencyTimer;
    private final Timer keywordExtractionTimer;
    
    private final AtomicLong activeAgents = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    
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
}
