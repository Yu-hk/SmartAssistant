package com.example.smartassistant.common.tool.client;

import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolRegistryAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void doesNotCreateClientWithoutToolGateway() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(ToolRegistryClient.class));
    }

    @Test
    void createsClientWhenToolInfrastructureIsAvailable() {
        contextRunner
                .withBean(ToolRegistry.class, ToolRegistry::new)
                .withBean(ToolGateway.class,
                        () -> new ToolGateway(new ToolRegistry(), List.of()))
                .run(context -> assertThat(context).hasSingleBean(ToolRegistryClient.class));
    }
}
