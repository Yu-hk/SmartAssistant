package com.example.smartassistant.config;

import com.example.smartassistant.common.skill.SkillPackageAutoConfiguration;
import com.example.smartassistant.common.skill.SkillPackageManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductSkillPackageConfigurationTest {

    @Test
    void actualAgentCallbacksExcludeUnboundLegacyMemory() {
        var config = new ProductAgentConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(config, "agentName", "product-service");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "systemPromptResource",
                new org.springframework.core.io.ClassPathResource("prompts/product-system-prompt.txt"));
        var model = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var ai = org.mockito.Mockito.mock(com.example.smartassistant.common.rag.advisor.AiChatService.class);
        org.mockito.Mockito.when(ai.buildChatClient(model)).thenReturn(org.springframework.ai.chat.client.ChatClient.create(model));
        var agent = config.productAgent(model,
                org.mockito.Mockito.mock(com.example.smartassistant.product.tool.ProductTools.class),
                org.mockito.Mockito.mock(com.example.smartassistant.product.tool.ProductMemoryTool.class),
                org.mockito.Mockito.mock(com.example.smartassistant.product.tool.KnowledgeQueryTool.class),
                org.mockito.Mockito.mock(com.example.smartassistant.service.monitoring.ProductMetricsCollector.class),
                null, ai, null);
        @SuppressWarnings("unchecked")
        var callbacks = (List<org.springframework.ai.tool.ToolCallback>)
                org.springframework.test.util.ReflectionTestUtils.getField(agent, "presetTools");
        org.assertj.core.api.Assertions.assertThat(callbacks).extracting(tool -> tool.getToolDefinition().name())
                .contains("getPrice", "checkStock").doesNotContain("savePreference", "recallMemories");
    }

    @Test
    void loadsVersionedProductSkillsAndValidatesRealTools() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                SkillPackageAutoConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.name=skill-packages",
                        "spring.cloud.nacos.config.import-check.enabled=false",
                        "spring.main.banner-mode=off",
                        "spring.main.log-startup-info=false")
                .run()) {
            SkillPackageManager manager = context.getBean(SkillPackageManager.class);
            assertEquals(6, manager.getAgentSkills("product-service").size());
            assertTrue(manager.validateAgentSkills("product-service", List.of(
                    "listRecommendedProducts", "queryProductInfo", "getPrice", "checkStock",
                    "queryKnowledge")).isEmpty());
        }
    }
}
