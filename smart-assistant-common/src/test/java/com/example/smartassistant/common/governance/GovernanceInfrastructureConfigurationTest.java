package com.example.smartassistant.common.governance;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertSame;

class GovernanceInfrastructureConfigurationTest {

    @Test
    void exposesBudgetsOutsideOptionalAdvisorConfiguration() {
        try (var context = new AnnotationConfigApplicationContext(
                GovernanceInfrastructureConfiguration.class)) {
            assertSame(InvocationBudgetRegistry.shared(),
                    context.getBean(InvocationBudgetRegistry.class));
            context.getBean(CallLimitProperties.class);
        }
    }
}
