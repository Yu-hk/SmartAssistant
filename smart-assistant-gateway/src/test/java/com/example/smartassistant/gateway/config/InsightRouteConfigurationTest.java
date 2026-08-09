package com.example.smartassistant.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightRouteConfigurationTest {

    @Test
    void shouldRouteBothPublicAndBareInsightPathsToConsumer() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertTrue(hasPathPredicate(properties, "/assistant/api/insight/**"));
        assertTrue(hasPathPredicate(properties, "/api/insight/**"));
    }

    private boolean hasPathPredicate(Properties properties, String path) {
        return properties.stringPropertyNames().stream()
                .filter(name -> name.contains("spring.cloud.gateway.routes"))
                .filter(name -> name.contains("predicates"))
                .map(properties::getProperty)
                .anyMatch(value -> value != null && value.contains(path));
    }
}
