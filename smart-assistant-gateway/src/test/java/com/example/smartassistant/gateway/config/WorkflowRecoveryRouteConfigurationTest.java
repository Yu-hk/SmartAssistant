package com.example.smartassistant.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRecoveryRouteConfigurationTest {

    @Test
    void shouldRouteBareRecoveryEndpointsToRouterBeforeBroadAdminRoute() {
        Properties properties = loadApplicationProperties();
        String recoveryPrefix = routePrefix(properties, "router-workflow-recovery-bare");
        String consumerPrefix = routePrefix(properties, "consumer-service-bare");

        assertTrue(routeHasPath(properties, recoveryPrefix, "/api/router/workflows/**"));
        assertTrue(routeHasPath(properties, recoveryPrefix, "/api/router/graph/**"));
        assertTrue(routeHasPath(properties, recoveryPrefix, "/api/admin/workflows/**"));
        assertFalse(routeHasFilter(properties, recoveryPrefix, "StripPrefix"));
        assertTrue(routeIndex(recoveryPrefix) < routeIndex(consumerPrefix),
                "Recovery route must precede consumer's broad /api/admin/** route");
        assertTrue(routeHasPath(properties, consumerPrefix, "/api/notifications/**"));
    }

    private Properties loadApplicationProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    private boolean routeHasPath(Properties properties, String routePrefix, String path) {
        return properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith(routePrefix + ".predicates"))
                .map(properties::getProperty)
                .anyMatch(value -> value != null && value.contains(path));
    }

    private boolean routeHasFilter(Properties properties, String routePrefix, String filter) {
        return properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith(routePrefix + ".filters"))
                .map(properties::getProperty)
                .anyMatch(value -> value != null && value.contains(filter));
    }

    private String routePrefix(Properties properties, String routeId) {
        return properties.stringPropertyNames().stream()
                .filter(name -> name.matches(
                        "spring\\.cloud\\.gateway\\.server\\.webflux\\.routes\\[\\d+].id"))
                .filter(name -> routeId.equals(properties.getProperty(name)))
                .map(name -> name.substring(0, name.length() - ".id".length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Route not found: " + routeId));
    }

    private int routeIndex(String routePrefix) {
        int start = routePrefix.indexOf('[') + 1;
        int end = routePrefix.indexOf(']', start);
        return Integer.parseInt(routePrefix.substring(start, end));
    }
}
