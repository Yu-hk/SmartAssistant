package com.example.smartassistant.toolregistry.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerConfigBeanNameTest {

    @Test
    void usesModuleSpecificConfigurationBeanName() {
        try (var context = new AnnotationConfigApplicationContext()) {
            new AnnotatedBeanDefinitionReader(context).register(McpServerConfig.class);

            assertThat(context.containsBeanDefinition("toolRegistryMcpServerConfig")).isTrue();
            assertThat(context.containsBeanDefinition("mcpServerConfig")).isFalse();
        }
    }

    @Test
    void exposesRegistryMcpToolsOnlyWhenExplicitlyEnabled() throws Exception {
        var method = McpServerConfig.class.getDeclaredMethod(
                "registryToolSpecifications",
                com.example.smartassistant.toolregistry.service.RegistryService.class,
                McpToolRegistryAdapter.class);

        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("tool-registry.mcp.exposure-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
    }
}
