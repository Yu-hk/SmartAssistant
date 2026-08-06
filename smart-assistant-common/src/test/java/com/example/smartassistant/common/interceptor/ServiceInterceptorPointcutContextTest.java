package com.example.smartassistant.common.interceptor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceInterceptorPointcutContextTest {

    @Test
    void directComponentScanRegistrationUsesSafeDefaults() {
        try (var context = new AnnotationConfigApplicationContext()) {
            AopConfigUtils.registerAspectJAutoProxyCreatorIfNecessary(context);
            context.register(ServiceInterceptorAutoConfiguration.class, MeterRegistryConfiguration.class);

            context.refresh();

            assertThat(context.getBean(PerformanceMonitorInterceptor.class)).isNotNull();
            assertThat(context.getBean(org.springframework.aop.Advisor.class)).isNotNull();
        }
    }

    @Test
    void finalInfrastructureBeanOutsideConfiguredPackagesIsNotProxied() {
        try (var context = new AnnotationConfigApplicationContext()) {
            AopConfigUtils.registerAspectJAutoProxyCreatorIfNecessary(context);
            context.register(TestConfiguration.class);

            context.refresh();

            assertThat(context.getBean(FinalInfrastructurePostProcessor.class))
                    .isExactlyInstanceOf(FinalInfrastructurePostProcessor.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableServiceInterceptor(basePackages = "com.example.smartassistant.testtarget")
    static class TestConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        FinalInfrastructurePostProcessor finalInfrastructurePostProcessor() {
            return new FinalInfrastructurePostProcessor();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    static final class FinalInfrastructurePostProcessor implements BeanPostProcessor {
    }
}
