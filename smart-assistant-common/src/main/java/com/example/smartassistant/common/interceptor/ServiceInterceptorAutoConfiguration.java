/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotationMetadata;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link EnableServiceInterceptor} 注解的导入配置。
 *
 * <p>动态创建 AOP Advisor 将 {@link ServiceInterceptorChain} 织入指定包路径。
 * 使用程序化 AOP（非 @AspectJ），支持运行时配置包路径。
 */
@Configuration
public class ServiceInterceptorAutoConfiguration implements ImportAware {

    private static final Logger log = LoggerFactory.getLogger(ServiceInterceptorAutoConfiguration.class);

    private AnnotationAttributes annotationAttributes;

    @Override
    public void setImportMetadata(AnnotationMetadata importMetadata) {
        this.annotationAttributes = AnnotationAttributes.fromMap(
                importMetadata.getAnnotationAttributes(EnableServiceInterceptor.class.getName(), false));
        if (this.annotationAttributes == null) {
            throw new IllegalArgumentException(
                    "@EnableServiceInterceptor is not present on importing class");
        }
    }

    /**
     * 创建拦截器链 Bean。
     */
    @Bean
    public ServiceInterceptorChain serviceInterceptorChain(
            List<ServiceInterceptor> interceptors) {
        List<ServiceInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort(Comparator.comparingInt(ServiceInterceptor::getOrder));
        log.info("[Interceptor] Registered {} interceptors: {}",
                sorted.size(),
                sorted.stream().map(i -> i.getClass().getSimpleName()).collect(Collectors.joining(", ")));
        return ServiceInterceptorChain.fromBeans(sorted);
    }

    /**
     * 创建性能监控拦截器 Bean。
     */
    @Bean
    public PerformanceMonitorInterceptor performanceMonitorInterceptor(
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        long slowThreshold = annotationAttributes.getNumber("slowThresholdMs");
        return new PerformanceMonitorInterceptor(slowThreshold, meterRegistry);
    }

    /**
     * 创建日志拦截器 Bean（可选，默认关闭）。
     */
    @Bean
    public LoggingInterceptor loggingInterceptor() {
        return new LoggingInterceptor();
    }

    /**
     * 创建程序化 AOP Advisor，将拦截器链织入指定包路径。
     *
     * <p>此 Advisor 使用 {@link ServiceInterceptorMethodInterceptor} 作为环绕通知，
     * 拦截指定包路径下所有方法的调用。
     */
    @Bean
    public Advisor serviceInterceptorAdvisor(
            ServiceInterceptorChain interceptorChain) {
        String[] basePackages = annotationAttributes.getStringArray("basePackages");

        if (basePackages == null || basePackages.length == 0) {
            log.warn("[Interceptor] No basePackages configured, advisor will not match any methods");
        }

        // 使用 AnnotationMatchingPointcut 也可以，但我们需要按包路径匹配
        // 改用自定义的 MethodBeforeAdvice + 包路径匹配
        ServiceInterceptorMethodInterceptor advice =
                new ServiceInterceptorMethodInterceptor(interceptorChain, basePackages);

        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(advice);
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        return advisor;
    }

    /**
     * 环绕通知：将 ServiceInterceptorChain 织入目标方法。
     */
    static class ServiceInterceptorMethodInterceptor
            implements org.aopalliance.intercept.MethodInterceptor, Ordered {

        private final ServiceInterceptorChain chain;
        private final String[] basePackages;

        ServiceInterceptorMethodInterceptor(ServiceInterceptorChain chain, String[] basePackages) {
            this.chain = chain;
            this.basePackages = basePackages;
        }

        @Override
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
            Object target = invocation.getThis();
            Method method = invocation.getMethod();
            Object[] args = invocation.getArguments();

            // 跳过基础设施 Bean
            if (target instanceof AopInfrastructureBean) {
                return invocation.proceed();
            }

            // 包路径匹配检查
            String className = target.getClass().getName();
            if (!matchesBasePackage(className)) {
                return invocation.proceed();
            }

            // 跳过 Object 类方法
            if (method.getDeclaringClass() == Object.class) {
                return invocation.proceed();
            }

            InterceptorContext context = new InterceptorContext(
                    target, method, args, className, method.getName());

            try {
                // 前置处理
                context = chain.applyBefore(context);

                // 执行目标方法
                Object result = invocation.proceed();

                // 后置处理
                return chain.applyAfter(context, result);
            } catch (Throwable e) {
                chain.applyException(context, e);
                throw e;
            } finally {
                chain.applyCompletion(context);
            }
        }

        private boolean matchesBasePackage(String className) {
            if (basePackages == null || basePackages.length == 0) {
                return false;
            }
            for (String pkg : basePackages) {
                if (pkg != null && !pkg.isEmpty() && className.startsWith(pkg)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE - 100;
        }
    }
}
