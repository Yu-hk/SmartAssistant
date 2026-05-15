/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * MDC 追踪过滤器（Food Service）
 * 从 HTTP Header 中提取追踪上下文并设置到 MDC
 */
@Component
@Order(1)  // 最高优先级，确保在其他 Filter 之前执行
public class TracingFilter extends OncePerRequestFilter {
    
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String THREAD_ID_HEADER = "X-Thread-Id";
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain)
            throws ServletException, IOException {
        
        try {
            // 从 HTTP Header 提取追踪上下文
            String traceId = request.getHeader(TRACE_ID_HEADER);
            String requestId = request.getHeader(REQUEST_ID_HEADER);
            String threadId = request.getHeader(THREAD_ID_HEADER);
            
            // 设置到 MDC
            if (traceId != null && !traceId.isEmpty()) {
                MDC.put("traceId", traceId);
            }
            if (requestId != null && !requestId.isEmpty()) {
                MDC.put("requestId", requestId);
            }
            if (threadId != null && !threadId.isEmpty()) {
                MDC.put("threadId", threadId);
            }
            
            // 继续过滤链
            filterChain.doFilter(request, response);
            
        } finally {
            // 清理 MDC（防止内存泄漏）
            MDC.clear();
        }
    }
}
