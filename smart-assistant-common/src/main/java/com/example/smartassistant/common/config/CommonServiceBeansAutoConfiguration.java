package com.example.smartassistant.common.config;

import com.example.smartassistant.common.observability.OpsMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 common 中无 {@code @Component} 注解、但被各业务服务通过构造函数强制注入的普通类为 Spring Bean。
 * <p>示例：{@code OpsMetrics} 在 router 的 {@code RouteFinalizer} 中作为强制依赖注入，
 * 但该类本身无 {@code @Component}，需在此显式注册（无参构造）。</p>
 * <p>注意：{@code AgentEventBus}/{@code ControlPlaneEventBus} 在 router 中均为
 * {@code @Autowired(required = false)}（可选），缺 Bean 不阻塞启动，无需在此注册。</p>
 */
@Configuration
public class CommonServiceBeansAutoConfiguration {

    @Bean
    public OpsMetrics opsMetrics() {
        return new OpsMetrics();
    }
}
