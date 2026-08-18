package com.example.smartassistant.consumer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ApplicationYamlTest {

    @Test
    void applicationYamlIsSyntacticallyValid() {
        assertDoesNotThrow(() -> new YamlPropertySourceLoader().load(
                "consumer-application", new ClassPathResource("application.yml")));
    }
}
