package com.example.smartassistant.common.security;

import com.example.smartassistant.common.gateway.tool.hook.SanitizeHook;
import com.example.smartassistant.common.observability.ChatModelCompletionContentObservationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PiiPolicyConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PiiPolicyConfiguration.class))
            .withBean(SanitizeHook.class)
            .withBean(ChatModelCompletionContentObservationFilter.class);

    @Test
    void suppliesOneSharedPolicyToCommonInfrastructureOutsideRag() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PiiPolicyEngine.class);
            assertThat(context.getBean(PiiPolicyEngine.class)).isSameAs(PiiPolicyEngine.shared());
            assertThat(context).hasSingleBean(SanitizeHook.class);
            assertThat(context).hasSingleBean(ChatModelCompletionContentObservationFilter.class);
        });
    }

    @Test
    void backsOffWhenApplicationProvidesPolicyEngine() {
        PiiPolicyEngine custom = new PiiPolicyEngine();
        contextRunner.withBean(PiiPolicyEngine.class, () -> custom)
                .run(context -> assertThat(context.getBean(PiiPolicyEngine.class)).isSameAs(custom));
    }
}
