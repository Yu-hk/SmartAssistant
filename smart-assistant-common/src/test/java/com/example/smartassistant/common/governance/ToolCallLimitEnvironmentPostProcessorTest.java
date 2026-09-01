package com.example.smartassistant.common.governance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCallLimitEnvironmentPostProcessorTest {

    @Test
    void suppliesSpringAiNativeToolLimitDefaults() {
        StandardEnvironment environment = new StandardEnvironment();
        new ToolCallLimitEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertEquals("8", environment.resolvePlaceholders(environment.getProperty(
                "spring.ai.tools.limits.max-calls-per-tool-default")));
        assertEquals("24", environment.resolvePlaceholders(environment.getProperty(
                "spring.ai.tools.limits.max-total-tool-calls")));
        assertEquals("THROW", environment.getProperty("spring.ai.tools.limits.on-limit-exceeded"));
    }
}
