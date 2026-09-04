package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ReActProfileAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ReActProfileAutoConfiguration.class));

    @Test
    void bindsProfilesAndFillsMissingValuesFromDefault() {
        contextRunner
                .withPropertyValues(
                        "smartassistant.react.profiles.mcp.max-iterations=9",
                        "smartassistant.react.profiles.mcp.timeout-ms=12345")
                .run(context -> {
                    ReActProfile profile = context.getBean(ReActProfileRegistry.class).get("mcp");
                    assertThat(profile.maxIterations()).isEqualTo(9);
                    assertThat(profile.timeoutMs()).isEqualTo(12345);
                    assertThat(profile.contextWindow()).isEqualTo(ReActProfile.DEFAULT.contextWindow());
                });
    }

    @Test
    void backsOffForApplicationRegistry() {
        ReActProfileRegistry custom = new ReActProfileRegistry(java.util.Map.of());
        contextRunner.withBean(ReActProfileRegistry.class, () -> custom)
                .run(context -> assertThat(context.getBean(ReActProfileRegistry.class)).isSameAs(custom));
    }
}
