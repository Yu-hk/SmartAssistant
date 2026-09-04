package com.example.smartassistant.common.governance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceInfrastructureConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GovernanceInfrastructureConfiguration.class));

    @Test
    void exposesBudgetsOutsideOptionalAdvisorConfiguration() {
        contextRunner.run(context -> {
            assertThat(context.getBean(InvocationBudgetRegistry.class))
                    .isSameAs(InvocationBudgetRegistry.shared());
            assertThat(context).hasSingleBean(CallLimitProperties.class);
        });
    }

    @Test
    void backsOffForApplicationBudgetRegistry() {
        InvocationBudgetRegistry custom = new InvocationBudgetRegistry();
        contextRunner.withBean(InvocationBudgetRegistry.class, () -> custom)
                .run(context -> assertThat(context.getBean(InvocationBudgetRegistry.class)).isSameAs(custom));
    }
}
