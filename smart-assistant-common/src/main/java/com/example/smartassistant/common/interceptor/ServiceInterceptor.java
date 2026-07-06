/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.interceptor;

import org.springframework.core.Ordered;

/**
 * 统一的服务拦截器 SPI。
 *
 * <p>参考 Snail AI 的 {@code SnailAiInterceptor} 设计，提供请求前/响应后的拦截点。
 * 通过 {@link Ordered#getOrder()} 控制执行顺序，数值越小优先级越高。
 *
 * <p>内置实现：
 * <ul>
 *   <li>{@link PerformanceMonitorInterceptor} — 性能监控（Order=1000）</li>
 *   <li>{@link LoggingInterceptor} — 请求/响应日志（Order=2000）</li>
 * </ul>
 *
 * <p>使用方式：实现此接口并注册为 Spring Bean，自动被 {@link ServiceInterceptorChain} 发现。
 */
public interface ServiceInterceptor extends Ordered {

    /**
     * 请求前处理。
     *
     * @param context 请求上下文，包含方法签名、参数等信息
     * @return 可修改后的上下文（允许拦截器修改参数）
     */
    default InterceptorContext beforeInvoke(InterceptorContext context) {
        return context;
    }

    /**
     * 请求后处理（正常返回）。
     *
     * @param context 请求上下文
     * @param result  方法返回值
     * @return 可修改后的返回值
     */
    default Object afterInvoke(InterceptorContext context, Object result) {
        return result;
    }

    /**
     * 异常处理。
     *
     * @param context   请求上下文
     * @param exception 抛出的异常
     */
    default void onException(InterceptorContext context, Throwable exception) {
        // 默认空实现
    }

    /**
     * 最终处理（无论正常/异常都会执行，类似 finally）。
     *
     * @param context 请求上下文
     */
    default void afterCompletion(InterceptorContext context) {
        // 默认空实现
    }
}
