package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.common.skill.SkillPackageAutoConfiguration;
import com.example.smartassistant.common.skill.SkillPackageManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterSkillPackageConfigurationTest {

    @Test
    void loadsTrustedPlanningSkillsWithValidDependencies() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                SkillPackageAutoConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.name=skill-packages",
                        "spring.cloud.nacos.config.import-check.enabled=false",
                        "spring.main.banner-mode=off",
                        "spring.main.log-startup-info=false")
                .run()) {
            SkillPackageManager manager = context.getBean(SkillPackageManager.class);
            assertEquals(4, manager.getAgentSkills("router-service").size());
            assertTrue(manager.validateAgentSkills("router-service", java.util.List.of()).isEmpty());
        }
    }
}
