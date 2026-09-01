package com.example.smartassistant.common.governance;

import com.example.smartassistant.common.security.PiiLogbackInstaller;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Supplies low-precedence defaults for Spring AI 2.0.1's native ToolCallingManager.
 * Application/Nacos/environment values remain able to override every limit.
 */
@SuppressWarnings("removal")
public class ToolCallLimitEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        PiiLogbackInstaller.install();
        environment.getPropertySources().addLast(new MapPropertySource(
                "smartAssistantSpringAiToolLimitDefaults",
                Map.of(
                        "spring.ai.tools.limits.max-calls-per-tool-default",
                        "${assistant.call-limits.tool.spring-ai-max-per-tool:8}",
                        "spring.ai.tools.limits.max-total-tool-calls",
                        "${assistant.call-limits.tool.spring-ai-max-total:24}",
                        "spring.ai.tools.limits.on-limit-exceeded", "THROW")));
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
