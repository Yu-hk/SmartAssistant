/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @Tool 方法统一日志切面 + Micrometer 指标采集。
 *
 * <p>拦截所有标注 {@code @org.springframework.ai.tool.annotation.Tool} 的方法，
 * 记录：方法名、requestId、输入参数、输出结果、执行耗时。
 * 同时采集 Prometheus 指标：
 * <ul>
 *   <li>{@code a2a_tool_call_total{tool="xxx"}} — 工具调用次数</li>
 *   <li>{@code a2a_tool_execution_latency{tool="xxx"}} — 工具执行耗时</li>
 *   <li>{@code a2a_tool_error_total{tool="xxx"}} — 工具错误次数</li>
 * </ul>
 * </p>
 */
@Aspect
@Component
public class ToolLogAspect {

    private static final Logger log = LoggerFactory.getLogger(ToolLogAspect.class);

    /** 输出结果截断长度 */
    private static final int MAX_RESULT_LENGTH = 500;

    /** 输入参数截断长度 */
    private static final int MAX_PARAM_LENGTH = 300;

    /** Micrometer 指标注册表 */
    private final MeterRegistry meterRegistry;

    /** 工具调用计数器缓存：toolName → Counter */
    private final ConcurrentHashMap<String, Counter> toolCallCounters = new ConcurrentHashMap<>();

    /** 工具错误计数器缓存 */
    private final ConcurrentHashMap<String, Counter> toolErrorCounters = new ConcurrentHashMap<>();

    /** 工具执行计时器缓存 */
    private final ConcurrentHashMap<String, Timer> toolLatencyTimers = new ConcurrentHashMap<>();

    public ToolLogAspect(@Autowired(required = false) MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 拦截所有 @Tool 注解的方法（全限定类名匹配 Spring AI 注解）。
     */
    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object logToolCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String requestId = ToolLogContext.getRequestId();
        String params = truncate(formatParams(joinPoint.getArgs()), MAX_PARAM_LENGTH);

        // 记录工具调用次数
        recordToolCall(methodName);

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;
            String resultStr = truncate(String.valueOf(result), MAX_RESULT_LENGTH);

            log.info("[Tool] method={} requestId={} params={} result={} duration={}ms",
                    methodName,
                    requestId != null ? requestId : "-",
                    params,
                    resultStr,
                    duration);

            // 记录工具执行耗时
            recordToolLatency(methodName, duration);

            return result;
        } catch (Throwable ex) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[Tool] method={} requestId={} params={} error={} duration={}ms",
                    methodName,
                    requestId != null ? requestId : "-",
                    params,
                    ex.getMessage(),
                    duration);

            // 记录工具错误
            recordToolError(methodName);
            recordToolLatency(methodName, duration);
            throw ex;
        }
    }

    // ==================== Micrometer 指标 ====================

    private void recordToolCall(String toolName) {
        if (meterRegistry == null) return;
        toolCallCounters.computeIfAbsent(toolName, name ->
                Counter.builder("a2a_tool_call_total")
                        .description("Tool call count")
                        .tag("tool", name)
                        .register(meterRegistry)
        ).increment();
    }

    private void recordToolError(String toolName) {
        if (meterRegistry == null) return;
        toolErrorCounters.computeIfAbsent(toolName, name ->
                Counter.builder("a2a_tool_error_total")
                        .description("Tool execution error count")
                        .tag("tool", name)
                        .register(meterRegistry)
        ).increment();
    }

    private void recordToolLatency(String toolName, long millis) {
        if (meterRegistry == null) return;
        toolLatencyTimers.computeIfAbsent(toolName, name ->
                Timer.builder("a2a_tool_execution_latency")
                        .description("Tool execution latency")
                        .tag("tool", name)
                        .register(meterRegistry)
        ).record(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * 格式化参数数组为 {key:value, ...} 风格。
     * 通过反射读取参数名（编译时加 -parameters）；回退时用 arg0, arg1...
     */
    private String formatParams(Object[] args) {
        if (args == null || args.length == 0) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("arg").append(i).append("=");
            String val = args[i] != null ? args[i].toString() : "null";
            sb.append(val.length() > 100 ? val.substring(0, 100) + "..." : val);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 截断过长的字符串，保留前 maxLen 字符 + "..." 后缀。
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "null";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(truncated)";
    }
}
