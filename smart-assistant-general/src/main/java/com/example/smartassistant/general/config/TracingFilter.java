/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Propagates the Router request id into the Agent's model-advisor context. */
@Component
@Order(1)
public class TracingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            putIfPresent("traceId", request.getHeader("X-Trace-Id"));
            putIfPresent("requestId", request.getHeader("X-Request-Id"));
            putIfPresent("threadId", request.getHeader("X-Thread-Id"));
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) MDC.put(key, value);
    }
}
