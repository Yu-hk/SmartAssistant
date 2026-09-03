package com.example.smartassistant.common.governance;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared invocation budgets required by both AI advisors and the tool gateway. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CallLimitProperties.class)
public class GovernanceInfrastructureConfiguration {

    @Bean
    public InvocationBudgetRegistry invocationBudgetRegistry() {
        return InvocationBudgetRegistry.shared();
    }
}
