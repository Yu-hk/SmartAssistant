package com.example.smartassistant.common.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 分布式追踪服务（公共模块）
 * <p>
 * 基于 Slf4j MDC 实现轻量级追踪，统一各服务追踪行为。
 * 所有服务统一引用此公共版本，消除 4 份重复代码。
 * 使用方式：在服务模块的 pom.xml 中依赖 smart-assistant-common 即可。
 */
@Component
public class DistributedTracingService {

    private static final Logger log = LoggerFactory.getLogger(DistributedTracingService.class);

    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";
    private static final String REQUEST_ID_KEY = "requestId";
    private static final String THREAD_ID_KEY = "threadId";

    /**
     * 开始追踪上下文
     */
    public void startTrace(String requestId, String threadId) {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null) {
            traceId = generateTraceId();
            MDC.put(TRACE_ID_KEY, traceId);
        }

        String spanId = generateSpanId();
        MDC.put(SPAN_ID_KEY, spanId);
        MDC.put(REQUEST_ID_KEY, requestId);

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
        String threadId = MDC.get(THREAD_ID_KEY);

        log.debug("[Tracing] 结束追踪: traceId={}, spanId={}, requestId={}, threadId={}",
            traceId, spanId, requestId, threadId);

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
            "threadId", MDC.get(THREAD_ID_KEY) != null ? MDC.get(THREAD_ID_KEY) : ""
        );
    }

    /**
     * 注入追踪上下文到日志
     */
    public void injectToLog(String message) {
        String traceId = MDC.get(TRACE_ID_KEY);
        String requestId = MDC.get(REQUEST_ID_KEY);
        String threadId = MDC.get(THREAD_ID_KEY);

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
