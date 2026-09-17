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

class OrderSkillPackageConfigurationTest {

    @Test
    void actualExtendedAgentCallbacksExcludeUnboundLegacyMemory() {
        var config = new OrderAgentConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(config, "agentName", "order-service");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "extendedToolsEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "systemPromptResource",
                new org.springframework.core.io.ClassPathResource("prompts/order-system-prompt.txt"));
        var model = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var ai = org.mockito.Mockito.mock(com.example.smartassistant.common.rag.advisor.AiChatService.class);
        org.mockito.Mockito.when(ai.buildChatClient(model)).thenReturn(org.springframework.ai.chat.client.ChatClient.create(model));
        var agent = config.orderAgent(model,
                org.mockito.Mockito.mock(com.example.smartassistant.order.tool.OrderTools.class),
                org.mockito.Mockito.mock(com.example.smartassistant.order.tool.OrderMemoryTool.class),
                org.mockito.Mockito.mock(com.example.smartassistant.order.tool.OrderAnalyticsTool.class),
                org.mockito.Mockito.mock(com.example.smartassistant.order.tool.OrderKnowledgeTool.class),
                org.mockito.Mockito.mock(com.example.smartassistant.order.tool.TextToSqlTool.class),
                org.mockito.Mockito.mock(com.example.smartassistant.order.tool.CouponTools.class),
                org.mockito.Mockito.mock(com.example.smartassistant.service.monitoring.OrderMetricsCollector.class),
                io.micrometer.observation.ObservationRegistry.NOOP, ai, null, null);
        @SuppressWarnings("unchecked")
        var callbacks = (List<org.springframework.ai.tool.ToolCallback>)
                org.springframework.test.util.ReflectionTestUtils.getField(agent, "presetTools");
        org.assertj.core.api.Assertions.assertThat(callbacks).extracting(tool -> tool.getToolDefinition().name())
                .contains("queryOrder", "queryUserCoupons").doesNotContain("savePreference", "recallMemories");
    }

    @Test
    void disablesExtendedSkillsWhenExtendedToolsAreOff() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                SkillPackageAutoConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.name=skill-packages",
                        "order.agent.extended-tools-enabled=false",
                        "spring.cloud.nacos.config.import-check.enabled=false",
                        "spring.main.banner-mode=off",
                        "spring.main.log-startup-info=false")
                .run()) {
            SkillPackageManager manager = context.getBean(SkillPackageManager.class);
            assertEquals(6, manager.getAgentSkills("order-service").size());
            assertTrue(manager.validateAgentSkills("order-service", List.of(
                    "queryOrder", "createOrder", "payOrder", "confirmAction", "cancelOrder",
                    "applyRefund", "shipOrder", "trackLogistics", "confirmDelivery")).isEmpty());
        }
    }

    @Test
    void enablesAndValidatesExtendedSkillsWithExtendedTools() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                SkillPackageAutoConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.name=skill-packages",
                        "order.agent.extended-tools-enabled=true",
                        "spring.cloud.nacos.config.import-check.enabled=false",
                        "spring.main.banner-mode=off",
                        "spring.main.log-startup-info=false")
                .run()) {
            SkillPackageManager manager = context.getBean(SkillPackageManager.class);
            assertEquals(9, manager.getAgentSkills("order-service").size());
            assertTrue(manager.validateAgentSkills("order-service", List.of(
                    "queryOrder", "createOrder", "payOrder", "confirmAction", "cancelOrder",
                    "applyRefund", "shipOrder", "trackLogistics", "confirmDelivery",
                    "queryOrdersByStatus", "countOrdersByStatus", "queryTopRefunds",
                    "queryUserRefunds", "textToSql", "queryOrderKnowledge",
                    "queryUserCoupons", "findBestCoupon")).isEmpty());
        }
    }
}
