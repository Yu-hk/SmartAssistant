/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.interceptor;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * 启用服务拦截器链。
 *
 * <p>在 Spring Boot 应用主类或配置类上标注此注解，即可激活统一拦截器机制。
 * 会自动注册 {@link PerformanceMonitorInterceptor} 和 {@link LoggingInterceptor}，
 * 并创建程序化 AOP Advisor 将拦截器链织入指定包路径。
 *
 * <p>使用示例：
 * <pre>{@code
 * @EnableServiceInterceptor(basePackages = {
 *     "com.example.smartassistant.consumer.controller",
 *     "com.example.smartassistant.consumer.service",
 *     "com.example.smartassistant.consumer.auth.mapper"
 * })
 * @SpringBootApplication
 * public class ConsumerApplication { ... }
 * }</pre>
 *
 * <p>替换旧方案：启用此注解后，可删除模块本地的
 * {@code PerformanceMonitorAspect} 和 {@code LogUtils} 重复实现。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(ServiceInterceptorAutoConfiguration.class)
public @interface EnableServiceInterceptor {

    /**
     * 需要拦截的包路径列表。
     * 支持 controller、service、mapper 子包自动匹配。
     */
    String[] basePackages() default {};

    /**
     * 慢方法阈值（毫秒），默认 1000ms。
     */
    long slowThresholdMs() default 1000;

    /**
     * 是否启用日志拦截器，默认启用。
     */
    boolean enableLogging() default false;
}
