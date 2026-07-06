/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.interceptor;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 拦截器上下文，在 {@link ServiceInterceptor} 各方法间传递。
 *
 * <p>包含方法签名、参数、目标对象等信息，拦截器可以通过
 * {@link #setAttribute(String, Object)} 在上下文中传递自定义数据。
 */
public class InterceptorContext {

    private final Object target;
    private final Method method;
    private final Object[] args;
    private final String targetClassName;
    private final String methodName;
    private final Map<String, Object> attributes;
    private long startTime;

    public InterceptorContext(Object target, Method method, Object[] args,
                              String targetClassName, String methodName) {
        this.target = target;
        this.method = method;
        this.args = args;
        this.targetClassName = targetClassName;
        this.methodName = methodName;
        this.attributes = new java.util.concurrent.ConcurrentHashMap<>();
        this.startTime = System.currentTimeMillis();
    }

    public Object getTarget() { return target; }
    public Method getMethod() { return method; }
    public Object[] getArgs() { return args; }
    public String getTargetClassName() { return targetClassName; }
    public String getMethodName() { return methodName; }
    public String getFullMethodName() { return targetClassName + "." + methodName; }
    public long getStartTime() { return startTime; }
    public long getDurationMs() { return System.currentTimeMillis() - startTime; }

    public void setStartTime(long startTime) { this.startTime = startTime; }

    public Object getAttribute(String key) { return attributes.get(key); }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Map<String, Object> getAttributes() { return attributes; }
}
