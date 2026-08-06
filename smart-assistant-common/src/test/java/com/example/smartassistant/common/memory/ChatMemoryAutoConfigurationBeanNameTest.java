package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryAutoConfigurationBeanNameTest {

    @Test
    void customMemoryDoesNotClaimSpringAiChatMemoryBeanName() {
        try (var context = new AnnotationConfigApplicationContext(ChatMemoryAutoConfiguration.class)) {
            assertThat(context.containsBean("smartAssistantChatMemory")).isTrue();
            assertThat(context.containsBean("chatMemory")).isFalse();
        }
    }
}
