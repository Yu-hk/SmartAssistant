package com.example.smartassistant.common.governance;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Shared invocation budgets required by both AI advisors and the tool gateway. */
@AutoConfiguration
@EnableConfigurationProperties(CallLimitProperties.class)
public class GovernanceInfrastructureConfiguration {

    @Bean
    @ConditionalOnMissingBean(InvocationBudgetRegistry.class)
    public InvocationBudgetRegistry invocationBudgetRegistry() {
        return InvocationBudgetRegistry.shared();
    }
}
