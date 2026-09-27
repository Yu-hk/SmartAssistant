package com.example.smartassistant.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class DataIntakeRouteConfigurationTest {
    @Test
    void productManagementPathsGoToIntakeBeforeConsumerAdminCatchAll() {
        var loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        String bare = routePrefix(properties, "data-intake-products");
        String prefixed = routePrefix(properties, "data-intake-products-prefixed");
        String consumer = routePrefix(properties, "consumer-service-bare");

        assertThat(properties.getProperty(bare + ".uri")).isEqualTo("lb://data-intake-service");
        assertThat(properties.getProperty(prefixed + ".uri")).isEqualTo("lb://data-intake-service");
        assertThat(properties.getProperty(bare + ".predicates[0]"))
                .contains("/api/admin/products", "/api/admin/products/**");
        assertThat(properties.getProperty(prefixed + ".predicates[0]"))
                .contains("/assistant/api/admin/products", "/assistant/api/admin/products/**");
        assertThat(properties.getProperty(prefixed + ".filters[0]")).isEqualTo("StripPrefix=1");
        assertThat(index(bare)).isLessThan(index(consumer));
        assertThat(index(prefixed)).isLessThan(index(consumer));
    }

    private static String routePrefix(Properties properties, String routeId) {
        return properties.stringPropertyNames().stream()
                .filter(name -> name.matches("spring\\.cloud\\.gateway\\.server\\.webflux\\.routes\\[\\d+].id"))
                .filter(name -> routeId.equals(properties.getProperty(name)))
                .map(name -> name.substring(0, name.length() - 3))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing route: " + routeId));
    }

    private static int index(String prefix) {
        return Integer.parseInt(prefix.substring(prefix.indexOf('[') + 1, prefix.indexOf(']')));
    }
}
