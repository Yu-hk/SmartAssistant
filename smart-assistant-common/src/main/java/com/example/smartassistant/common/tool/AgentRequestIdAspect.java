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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReactAgent.invoke() 拦截切面 —— 从 A2A 请求的 input 前缀中提取 requestId，
 * 设入 {@link ToolLogContext}（ThreadLocal + MDC），供下游 {@link ToolLogAspect} 使用。
 *
 * <p>Router 在调用下游 Agent 时，会在 instruction 前缀注入 {@code [requestId:xxx]}。
 * 本切面在 {@code ReactAgent.invoke()} 被调用时，从 input 参数中提取该标记，
 * 并设入 MDC，使后续 @Tool 方法的日志能关联到同一个 requestId。</p>
 *
 * <p>执行顺序：本切面需在 {@link ToolLogAspect} 之前执行，
 * 以确保 @Tool 方法被调用时 MDC 中已有 requestId。</p>
 *
 * @author SmartAssistant
 * @since 2026-05-18
 */
@Aspect
@Component
@Order(1)  // ⭐ 在 ToolLogAspect 之前执行，确保 MDC 已有 requestId
public class AgentRequestIdAspect {

    private static final Logger log = LoggerFactory.getLogger(AgentRequestIdAspect.class);

    /** 匹配 [requestId:xxx] 前缀 */
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^\\[requestId:([^\\]]+)\\]\\s*");

    /**
     * 拦截 ReactAgent.invoke() 方法，从 input 参数中提取 requestId 设入 MDC。
     *
     * <p>注意：ReactAgent 来自 Spring AI Alibaba，全限定名为
     * {@code com.alibaba.cloud.ai.graph.agent.ReactAgent}。</p>
     */
    @Around("execution(* com.alibaba.cloud.ai.graph.agent.ReactAgent.invoke(..))")
    public Object extractRequestId(ProceedingJoinPoint joinPoint) throws Throwable {
        String requestId = null;

        // 尝试从第一个参数（input String）中提取 [requestId:xxx]
        Object[] args = joinPoint.getArgs();
        if (args != null && args.length > 0 && args[0] instanceof String input) {
            Matcher matcher = REQUEST_ID_PATTERN.matcher(input);
            if (matcher.find()) {
                requestId = matcher.group(1);
            }
        }

        // 设置 requestId 到 MDC
        if (requestId != null) {
            ToolLogContext.setRequestId(requestId);
            log.debug("[AgentRequestId] 从 instruction 前缀提取 requestId={}", requestId);
        }

        try {
            return joinPoint.proceed();
        } finally {
            // 清除 MDC，避免线程池复用导致的 requestId 泄露
            ToolLogContext.clear();
        }
    }
}
