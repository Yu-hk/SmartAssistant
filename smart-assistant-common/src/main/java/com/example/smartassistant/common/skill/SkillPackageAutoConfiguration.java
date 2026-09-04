/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 技能包系统自动配置。
 *
 * <p>提供 {@link SkillPackageManager} Bean 并从配置属性加载预定义技能包。
 * 通过 {@code skill-package.enabled=true} 启用（默认开启）。
 */
@AutoConfiguration
@EnableConfigurationProperties(SkillPackageProperties.class)
@ConditionalOnProperty(name = "skill-package.enabled", havingValue = "true", matchIfMissing = true)
public class SkillPackageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SkillPackageAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SkillPackageManager.class)
    public SkillPackageManager skillPackageManager(SkillPackageProperties properties) {
        log.info("[SkillPackage] 技能包自动配置已启用");
        SkillPackageManager manager = new SkillPackageManager();
        // Register before exposing the manager Bean. Agent configuration may validate
        // skills while its own Bean is being created, so a separate InitializingBean
        // loader would run too late and make that validation observe an empty registry.
        properties.registerTo(manager);
        log.info("[SkillPackage] 技能包系统初始化完成，共 {} 个包",
                manager.getAll().size());
        return manager;
    }
}
