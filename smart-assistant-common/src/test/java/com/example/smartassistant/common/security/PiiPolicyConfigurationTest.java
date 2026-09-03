package com.example.smartassistant.common.security;

import com.example.smartassistant.common.gateway.tool.hook.SanitizeHook;
import com.example.smartassistant.common.observability.ChatModelCompletionContentObservationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertSame;

class PiiPolicyConfigurationTest {

    @Test
    void suppliesOneSharedPolicyToCommonInfrastructureOutsideRag() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(PiiPolicyConfiguration.class, SanitizeHook.class,
                    ChatModelCompletionContentObservationFilter.class);
            context.refresh();

            assertSame(PiiPolicyEngine.shared(), context.getBean(PiiPolicyEngine.class));
            context.getBean(SanitizeHook.class);
            context.getBean(ChatModelCompletionContentObservationFilter.class);
        }
    }
}
