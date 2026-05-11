package com.example.smartassistant.service.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 分布式追踪服务（Food Service）
 * 基于 Slf4j MDC 实现轻量级追踪
 */
@Component
public class DistributedTracingService {
    
    private static final Logger log = LoggerFactory.getLogger(DistributedTracingService.class);
    
    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";
    private static final String REQUEST_ID_KEY = "requestId";
    private static final String THREAD_ID_KEY = "threadId";  // ⭐
    
    public void startTrace(String requestId, String threadId) {  // ⭐ 添加 threadId 参数
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null) {
            traceId = generateTraceId();
            MDC.put(TRACE_ID_KEY, traceId);
        }
        
        String spanId = generateSpanId();
        MDC.put(SPAN_ID_KEY, spanId);
        
        if (requestId != null && !requestId.isEmpty() && MDC.get(REQUEST_ID_KEY) == null) {
            MDC.put(REQUEST_ID_KEY, requestId);
        }
        
        // ⭐ 设置 Thread ID
        if (threadId != null && !threadId.isEmpty() && MDC.get(THREAD_ID_KEY) == null) {
            MDC.put(THREAD_ID_KEY, threadId);
        }
        
        log.debug("[Tracing] Food 开始追踪: traceId={}, spanId={}, requestId={}, threadId={}", 
            traceId, spanId, requestId, threadId);
    }
    
    public void endTrace() {
        String traceId = MDC.get(TRACE_ID_KEY);
        String spanId = MDC.get(SPAN_ID_KEY);
        String requestId = MDC.get(REQUEST_ID_KEY);
        String threadId = MDC.get(THREAD_ID_KEY);  // ⭐
        
        log.debug("[Tracing] Food 结束追踪: traceId={}, spanId={}, requestId={}, threadId={}", 
            traceId, spanId, requestId, threadId);
        
        MDC.clear();
    }
    
    public Map<String, String> getCurrentContext() {
        return Map.of(
            "traceId", MDC.get(TRACE_ID_KEY) != null ? MDC.get(TRACE_ID_KEY) : "",
            "spanId", MDC.get(SPAN_ID_KEY) != null ? MDC.get(SPAN_ID_KEY) : "",
            "requestId", MDC.get(REQUEST_ID_KEY) != null ? MDC.get(REQUEST_ID_KEY) : "",
            "threadId", MDC.get(THREAD_ID_KEY) != null ? MDC.get(THREAD_ID_KEY) : ""  // ⭐
        );
    }
    
    public void injectToLog(String message) {
        String traceId = MDC.get(TRACE_ID_KEY);
        String requestId = MDC.get(REQUEST_ID_KEY);
        String threadId = MDC.get(THREAD_ID_KEY);  // ⭐
        
        if (traceId != null && requestId != null) {
            if (threadId != null) {
                log.info("[Trace: {}] [Request: {}] [Thread: {}] {}", traceId, requestId, threadId, message);
            } else {
                log.info("[Trace: {}] [Request: {}] {}", traceId, requestId, message);
            }
        } else {
            log.info(message);
        }
    }
    
    private String generateTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    
    private String generateSpanId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
