package com.example.smartassistant.common.error;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link ErrorRecoveryService} 为 Spring Bean。
 * <p>common 的 agent 体系（{@code AgentToolExecutor}、router 的 {@code RoutingToolChecker} 等）
 * 通过构造函数注入该服务，但 {@link ErrorRecoveryService} 本身是普通类（无 {@code @Component}），
 * 需在此显式注册 Bean，否则依赖它的 Bean 会报「No qualifying bean」。</p>
 */
@Configuration
public class ErrorRecoveryServiceAutoConfiguration {

    @Bean
    public ErrorRecoveryService errorRecoveryService() {
        return new ErrorRecoveryService();
    }
}
