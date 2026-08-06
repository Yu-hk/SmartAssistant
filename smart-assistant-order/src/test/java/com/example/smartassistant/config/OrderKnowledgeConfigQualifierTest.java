package com.example.smartassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class OrderKnowledgeConfigQualifierTest {

    @Test
    void orderKnowledgeBaseSelectsTheOrderSpecificReranker() {
        Method factoryMethod = java.util.Arrays.stream(OrderKnowledgeConfig.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("orderKnowledgeBase"))
                .findFirst()
                .orElseThrow();

        Qualifier qualifier = factoryMethod.getParameters()[2].getAnnotation(Qualifier.class);

        assertThat(qualifier).isNotNull();
        assertThat(qualifier.value()).isEqualTo("orderReranker");
    }
}
