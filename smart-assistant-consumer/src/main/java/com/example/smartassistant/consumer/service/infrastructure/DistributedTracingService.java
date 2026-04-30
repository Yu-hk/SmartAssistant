package com.example.smartassistant.consumer.service.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 分布式追踪服务
 * 基于 Slf4j MDC 实现轻量级追踪
 */
@Component
public class DistributedTracingService {
    
    private static final Logger log = LoggerFactory.getLogger(DistributedTracingService.class);
    
    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";
    private static final String REQUEST_ID_KEY = "requestId";
    private static final String THREAD_ID_KEY = "threadId";  // ⭐ 新增
    
    /**
     * 开始追踪上下文
     */
    public void startTrace(String requestId, String threadId) {  // ⭐ 添加 threadId 参数
        // 生成或复用 Trace ID
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null) {
            traceId = generateTraceId();
            MDC.put(TRACE_ID_KEY, traceId);
        }
        
        // 设置 Span ID
        String spanId = generateSpanId();
        MDC.put(SPAN_ID_KEY, spanId);
        
        // 设置 Request ID
        MDC.put(REQUEST_ID_KEY, requestId);
        
        // ⭐ 设置 Thread ID（会话标识）
        if (threadId != null && !threadId.isEmpty()) {
            MDC.put(THREAD_ID_KEY, threadId);
        }
        
        log.debug("[Tracing] 开始追踪: traceId={}, spanId={}, requestId={}, threadId={}", 
            traceId, spanId, requestId, threadId);
    }
    
    /**
     * 结束追踪上下文
     */
    public void endTrace() {
        String traceId = MDC.get(TRACE_ID_KEY);
        String spanId = MDC.get(SPAN_ID_KEY);
        String requestId = MDC.get(REQUEST_ID_KEY);
        String threadId = MDC.get(THREAD_ID_KEY);  // ⭐
        
        log.debug("[Tracing] 结束追踪: traceId={}, spanId={}, requestId={}, threadId={}", 
            traceId, spanId, requestId, threadId);
        
        // 清理 MDC
        MDC.clear();
    }
    
    /**
     * 获取当前追踪上下文
     */
    public Map<String, String> getCurrentContext() {
        return Map.of(
            "traceId", MDC.get(TRACE_ID_KEY) != null ? MDC.get(TRACE_ID_KEY) : "",
            "spanId", MDC.get(SPAN_ID_KEY) != null ? MDC.get(SPAN_ID_KEY) : "",
            "requestId", MDC.get(REQUEST_ID_KEY) != null ? MDC.get(REQUEST_ID_KEY) : "",
            "threadId", MDC.get(THREAD_ID_KEY) != null ? MDC.get(THREAD_ID_KEY) : ""  // ⭐
        );
    }
    
    /**
     * 注入追踪上下文到日志
     */
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
    
    /**
     * 生成 Trace ID
     */
    private String generateTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    
    /**
     * 生成 Span ID
     */
    private String generateSpanId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
