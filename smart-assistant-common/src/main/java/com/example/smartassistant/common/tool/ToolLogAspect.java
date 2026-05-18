/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @Tool 方法统一日志切面。
 *
 * <p>拦截所有标注 {@code @org.springframework.ai.tool.annotation.Tool} 的方法，
 * 记录：方法名、requestId、输入参数、输出结果、执行耗时。</p>
 *
 * <p>日志格式：</p>
 * <pre>
 *   [Tool] method=calculate requestId=abc123 params={expression:"3.14*5^2"} result="78.5" duration=3ms
 * </pre>
 *
 * <p>输出结果超长时自动截断（默认 500 字），避免日志膨胀。</p>
 *
 * @author SmartAssistant
 * @since 2026-05-18
 */
@Aspect
@Component
public class ToolLogAspect {

    private static final Logger log = LoggerFactory.getLogger(ToolLogAspect.class);

    /** 输出结果截断长度 */
    private static final int MAX_RESULT_LENGTH = 500;

    /** 输入参数截断长度 */
    private static final int MAX_PARAM_LENGTH = 300;

    /**
     * 拦截所有 @Tool 注解的方法（全限定类名匹配 Spring AI 注解）。
     */
    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object logToolCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String requestId = ToolLogContext.getRequestId();
        String params = truncate(formatParams(joinPoint.getArgs()), MAX_PARAM_LENGTH);

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
            return result;
        } catch (Throwable ex) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[Tool] method={} requestId={} params={} error={} duration={}ms",
                    methodName,
                    requestId != null ? requestId : "-",
                    params,
                    ex.getMessage(),
                    duration);
            throw ex;
        }
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
