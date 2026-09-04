package com.example.smartassistant.common.gateway.tool.mcp;

import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.meta.DiscoverToolsTool;
import com.example.smartassistant.common.json.Jackson2CompatibilityAutoConfiguration;
import com.example.smartassistant.common.tool.client.ToolRegistryAutoConfiguration;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpDiscoveryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    Jackson2CompatibilityAutoConfiguration.class,
                    ToolRegistryAutoConfiguration.class,
                    McpDiscoveryAutoConfiguration.class));

    @Test
    void staysInactiveWithoutToolGovernanceBeans() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(McpRegistryClientFactory.class);
            assertThat(context).doesNotHaveBean(McpRegistryDiscoveryClient.class);
            assertThat(context).doesNotHaveBean(McpBackendToolExecutor.class);
        });
    }

    @Test
    void createsMcpInfrastructureAfterToolGovernanceIsAvailable() {
        withGovernanceBeans().run(context -> {
            assertThat(context).hasSingleBean(McpRegistryClientFactory.class);
            assertThat(context).hasSingleBean(McpRegistryDiscoveryClient.class);
            assertThat(context).hasSingleBean(McpBackendToolExecutor.class);
            assertThat(context).hasSingleBean(McpToolCallbackFactory.class);
            assertThat(context).doesNotHaveBean(DiscoverToolsTool.class);
        });
    }

    @Test
    void enablesDiscoveryToolOnlyWhenExplicitlyConfigured() {
        withGovernanceBeans()
                .withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withPropertyValues("tool-registry.t2-mcp-discovery-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DiscoverToolsTool.class));
    }

    private ApplicationContextRunner withGovernanceBeans() {
        return contextRunner
                .withBean(ToolRegistry.class, () -> mock(ToolRegistry.class))
                .withBean(ToolGateway.class, () -> mock(ToolGateway.class));
    }
}
