package com.example.smartassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RagProductionAutoConfigurationPrimaryTest {

    @Test
    void productKnowledgeBaseIsThePrimaryKnowledgeBase() {
        var method = Arrays.stream(RagProductionAutoConfiguration.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("productKnowledgeBase"))
                .findFirst()
                .orElseThrow();

        assertThat(method.getAnnotation(Primary.class)).isNotNull();
    }
}
