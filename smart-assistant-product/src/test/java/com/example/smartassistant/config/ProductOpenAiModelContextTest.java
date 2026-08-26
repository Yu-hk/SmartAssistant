package com.example.smartassistant.config;

import com.example.smartassistant.common.model.tier.ModelTier;
import com.example.smartassistant.common.model.tier.TierModelAutoConfiguration;
import com.example.smartassistant.common.model.tier.TierModelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProductOpenAiModelContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ToolCallingAutoConfiguration.class,
                    OpenAiChatAutoConfiguration.class,
                    TierModelAutoConfiguration.class))
            .withPropertyValues(
                    "spring.ai.model.chat=openai",
                    "spring.ai.openai.api-key=test-key",
                    "spring.ai.openai.base-url=http://localhost:9999/v1",
                    "tier.light.model=qwen3.7-flash",
                    "tier.standard.model=qwen3.7-flash",
                    "tier.heavy.model=qwen3.7-plus");

    @Test
    void createsOpenAiCompatibleQwenTierRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("openAiChatModel");
            assertThat(context).hasSingleBean(TierModelRegistry.class);

            TierModelRegistry registry = context.getBean(TierModelRegistry.class);
            assertThat(registry.modelName(ModelTier.LIGHT)).isEqualTo("qwen3.7-flash");
            assertThat(registry.modelName(ModelTier.HEAVY)).isEqualTo("qwen3.7-plus");
            assertThat(registry.get(ModelTier.LIGHT).getOptions())
                    .isInstanceOf(OpenAiChatOptions.class);
        });
    }
}
