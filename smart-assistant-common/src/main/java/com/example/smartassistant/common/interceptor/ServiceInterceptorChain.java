/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 服务拦截器链——按 {@link ServiceInterceptor#getOrder()} 顺序执行。
 *
 * <p>参考 Snail AI 的 {@code SnailAiInterceptorChain} 实现：
 * <ul>
 *   <li>{@link #applyBefore(InterceptorContext)} — 按 Order 正序执行</li>
 *   <li>{@link #applyAfter(InterceptorContext, Object)} — 按 Order 逆序执行</li>
 *   <li>{@link #applyException(InterceptorContext, Throwable)} — 按 Order 正序执行异常回调</li>
 *   <li>{@link #applyCompletion(InterceptorContext)} — 按 Order 逆序执行 finally</li>
 * </ul>
 */
public class ServiceInterceptorChain {

    private static final Logger log = LoggerFactory.getLogger(ServiceInterceptorChain.class);

    private final List<ServiceInterceptor> interceptors;

    public ServiceInterceptorChain(List<ServiceInterceptor> interceptors) {
        // 正序排序
        this.interceptors = new ArrayList<>(interceptors);
        this.interceptors.sort(Comparator.comparingInt(ServiceInterceptor::getOrder));
    }

    /**
     * 按 Order 正序执行所有 beforeInvoke。
     */
    public InterceptorContext applyBefore(InterceptorContext context) {
        InterceptorContext ctx = context;
        for (ServiceInterceptor interceptor : interceptors) {
            try {
                ctx = interceptor.beforeInvoke(ctx);
            } catch (Exception e) {
                log.warn("[Interceptor] beforeInvoke error in {}: {}",
                        interceptor.getClass().getSimpleName(), e.getMessage());
            }
            if (ctx == null) {
                log.warn("[Interceptor] beforeInvoke returned null from {}, using original",
                        interceptor.getClass().getSimpleName());
                ctx = context;
            }
        }
        return ctx;
    }

    /**
     * 按 Order 逆序执行所有 afterInvoke。
     */
    public Object applyAfter(InterceptorContext context, Object result) {
        Object current = result;
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            try {
                current = interceptors.get(i).afterInvoke(context, current);
            } catch (Exception e) {
                log.warn("[Interceptor] afterInvoke error in {}: {}",
                        interceptors.get(i).getClass().getSimpleName(), e.getMessage());
            }
        }
        return current;
    }

    /**
     * 按 Order 正序执行所有 onException。
     */
    public void applyException(InterceptorContext context, Throwable exception) {
        for (ServiceInterceptor interceptor : interceptors) {
            try {
                interceptor.onException(context, exception);
            } catch (Exception e) {
                log.warn("[Interceptor] onException error in {}: {}",
                        interceptor.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * 按 Order 逆序执行所有 afterCompletion。
     */
    public void applyCompletion(InterceptorContext context) {
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            try {
                interceptors.get(i).afterCompletion(context);
            } catch (Exception e) {
                log.warn("[Interceptor] afterCompletion error in {}: {}",
                        interceptors.get(i).getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * 获取当前链中所有拦截器列表
     */
    public List<ServiceInterceptor> getInterceptors() {
        return new ArrayList<>(interceptors);
    }

    /**
     * 工厂方法：从 Spring 容器中自动发现所有 ServiceInterceptor Bean。
     */
    public static ServiceInterceptorChain fromBeans(List<ServiceInterceptor> interceptors) {
        Objects.requireNonNull(interceptors, "interceptors must not be null");
        return new ServiceInterceptorChain(interceptors);
    }

    /**
     * 获取原始目标对象（代理解包）
     */
    public static Class<?> getTargetClass(Object target) {
        return AopProxyUtils.ultimateTargetClass(target);
    }
}
