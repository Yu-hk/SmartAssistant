package com.example.smartassistant.common.observability;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChatModelCompletionContentObservationFilterContextTest {

    @Test
    void startsWithoutManagedPiiPolicyEngine() {
        assertDoesNotThrow(() -> {
            try (var context = new AnnotationConfigApplicationContext(
                    ChatModelCompletionContentObservationFilter.class)) {
                assertNotNull(context.getBean(ChatModelCompletionContentObservationFilter.class));
            }
        });
    }
}
