package com.example.smartassistant.router.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouterManagementConfigurationTest {

    @Test
    void disablesRabbitHealthIndicatorWhenWorkflowRecoveryIsDisabled() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("router-application", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();
        sources.forEach(propertySources::addLast);

        assertThat(environment.getProperty("router.graph.recovery.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("management.health.rabbit.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("always");
    }
}
