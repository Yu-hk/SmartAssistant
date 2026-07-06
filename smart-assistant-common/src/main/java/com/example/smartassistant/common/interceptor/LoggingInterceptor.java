/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 请求/响应日志拦截器。
 *
 * <p>记录每次方法调用的入参和出参，适合调试和审计场景。
 * Order=2000，在性能监控拦截器之后执行。
 */
public class LoggingInterceptor implements ServiceInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    /** 参数最大打印长度 */
    private static final int MAX_PARAM_LENGTH = 300;

    /** 结果最大打印长度 */
    private static final int MAX_RESULT_LENGTH = 500;

    @Override
    public InterceptorContext beforeInvoke(InterceptorContext context) {
        if (log.isDebugEnabled()) {
            log.debug("[INVOKE] {} | args={}",
                    context.getFullMethodName(),
                    truncate(formatArgs(context.getArgs()), MAX_PARAM_LENGTH));
        }
        return context;
    }

    @Override
    public Object afterInvoke(InterceptorContext context, Object result) {
        if (log.isDebugEnabled()) {
            log.debug("[RETURN] {} | result={} | duration={}ms",
                    context.getFullMethodName(),
                    truncate(String.valueOf(result), MAX_RESULT_LENGTH),
                    context.getDurationMs());
        }
        return result;
    }

    @Override
    public void onException(InterceptorContext context, Throwable exception) {
        log.warn("[EXCEPTION] {} | error={} | duration={}ms",
                context.getFullMethodName(),
                exception.getMessage(),
                context.getDurationMs());
    }

    private String formatArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            String val = args[i] != null ? args[i].toString() : "null";
            sb.append(val.length() > 100 ? val.substring(0, 100) + "..." : val);
        }
        sb.append("]");
        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "null";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(truncated)";
    }

    @Override
    public int getOrder() {
        return 2000;
    }
}
