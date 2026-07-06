/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.interceptor;

import com.example.smartassistant.common.util.LogUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 性能监控拦截器。
 *
 * <p>替代各模块重复的 {@code PerformanceMonitorAspect}，提供：
 * <ul>
 *   <li>方法执行耗时日志（>1s 输出 WARN，否则 DEBUG）</li>
 *   <li>Micrometer 耗时指标（p50/p95/p99）</li>
 *   <li>慢方法统计计数</li>
 * </ul>
 *
 * <p>Order=1000，在日志拦截器之前执行。
 */
public class PerformanceMonitorInterceptor implements ServiceInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PerformanceMonitorInterceptor.class);

    /** 慢方法阈值（毫秒），超过此值输出 WARN 级别日志 */
    private final long slowThresholdMs;

    private final MeterRegistry meterRegistry;

    /** 方法执行计时器缓存 */
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public PerformanceMonitorInterceptor(long slowThresholdMs,
                                         @Autowired(required = false) MeterRegistry meterRegistry) {
        this.slowThresholdMs = slowThresholdMs > 0 ? slowThresholdMs : 1000L;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public InterceptorContext beforeInvoke(InterceptorContext context) {
        context.setStartTime(System.currentTimeMillis());
        return context;
    }

    @Override
    public Object afterInvoke(InterceptorContext context, Object result) {
        long duration = context.getDurationMs();
        String methodName = context.getFullMethodName();

        // 日志输出
        LogUtils.logPerformance(methodName, duration, "SUCCESS");

        // Micrometer 指标
        recordLatency(methodName, duration);

        return result;
    }

    @Override
    public void onException(InterceptorContext context, Throwable exception) {
        long duration = context.getDurationMs();
        String methodName = context.getFullMethodName();

        LogUtils.logPerformance(methodName, duration, "FAILED");

        if (duration > slowThresholdMs) {
            log.error("[SLOW_FAIL] {} | duration={}ms | error={}",
                    methodName, duration, exception.getMessage());
        }

        recordLatency(methodName, duration);
    }

    private void recordLatency(String methodName, long millis) {
        if (meterRegistry == null) return;
        timerCache.computeIfAbsent(methodName, name ->
                Timer.builder("a2a_method_execution_latency")
                        .description("Method execution latency")
                        .tag("method", name)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry)
        ).record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public int getOrder() {
        return 1000;
    }
}
