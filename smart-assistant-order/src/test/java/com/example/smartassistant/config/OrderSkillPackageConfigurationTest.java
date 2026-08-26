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
            assertEquals(10, manager.getAgentSkills("order-service").size());
            assertTrue(manager.validateAgentSkills("order-service", List.of(
                    "queryOrder", "createOrder", "payOrder", "confirmAction", "cancelOrder",
                    "applyRefund", "shipOrder", "trackLogistics", "confirmDelivery",
                    "queryOrdersByStatus", "countOrdersByStatus", "queryTopRefunds",
                    "queryUserRefunds", "textToSql", "queryOrderKnowledge",
                    "queryUserCoupons", "findBestCoupon", "savePreference", "recallMemories")).isEmpty());
        }
    }
}
