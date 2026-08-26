package com.example.smartassistant.common.skill;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillPackageAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SkillPackageAutoConfiguration.class, ObserverConfiguration.class)
            .withPropertyValues(
                    "skill-package.packages[0].id=test-skill",
                    "skill-package.packages[0].name=Test Skill",
                    "skill-package.packages[0].version=1.0.0",
                    "skill-package.packages[0].instruction=Use the test capability.",
                    "skill-package.packages[0].bind-agents[0]=test-agent");

    @Test
    void exposesPopulatedManagerToDependentBeans() {
        contextRunner.run(context -> {
            RegistrySnapshot snapshot = context.getBean(RegistrySnapshot.class);
            assertEquals(1, snapshot.totalSkills());
            assertEquals(1, snapshot.agentSkills());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ObserverConfiguration {

        @Bean
        RegistrySnapshot registrySnapshot(SkillPackageManager manager) {
            return new RegistrySnapshot(
                    manager.getAll().size(),
                    manager.getAgentSkills("test-agent").size());
        }
    }

    private record RegistrySnapshot(int totalSkills, int agentSkills) {
    }
}
