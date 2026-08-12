package com.example.smartassistant.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRouteConfigurationTest {

    @Test
    void shouldRoutePrefixedAndBareUserPathsWithoutStrippingBareApiPrefix() {
        Properties properties = loadApplicationProperties();

        assertTrue(routeHasPath(properties, "user-service", "/assistant/api/auth/**"));
        assertTrue(routeHasPath(properties, "user-service", "/assistant/api/user/**"));
        assertTrue(routeHasFilter(properties, "user-service", "StripPrefix=1"));

        assertTrue(routeHasPath(properties, "user-service-bare", "/api/auth/**"));
        assertTrue(routeHasPath(properties, "user-service-bare", "/api/user/**"));
        assertFalse(routeHasFilter(properties, "user-service-bare", "StripPrefix"));
    }

    @Test
    void shouldWhitelistBothPrefixedAndBareAuthenticationEndpoints() {
        Properties properties = loadApplicationProperties();
        List<String> whiteList = Arrays.stream(properties
                        .getProperty("gateway.security.white-list", "")
                        .split(","))
                .map(String::trim)
                .toList();

        assertTrue(whiteList.containsAll(List.of(
                "/assistant/api/auth/login",
                "/assistant/api/auth/register",
                "/assistant/api/auth/refresh",
                "/api/auth/login",
                "/api/auth/register",
                "/api/auth/refresh",
                "/assistant/api/gateway/token/**",
                "/assistant/api/public/**",
                "/actuator/health",
                "/actuator/info",
                "/assistant/api/router/agents/**"
        )));
    }

    private Properties loadApplicationProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    private boolean routeHasPath(Properties properties, String routeId, String path) {
        String routePrefix = routePrefix(properties, routeId);
        return properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith(routePrefix + ".predicates"))
                .map(properties::getProperty)
                .anyMatch(value -> value != null && value.contains(path));
    }

    private boolean routeHasFilter(Properties properties, String routeId, String filter) {
        String routePrefix = routePrefix(properties, routeId);
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
}
