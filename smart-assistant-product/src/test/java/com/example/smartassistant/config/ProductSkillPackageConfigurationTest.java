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
            assertEquals(7, manager.getAgentSkills("product-service").size());
            assertTrue(manager.validateAgentSkills("product-service", List.of(
                    "listRecommendedProducts", "queryProductInfo", "getPrice", "checkStock",
                    "queryKnowledge", "savePreference", "recallMemories")).isEmpty());
        }
    }
}
