/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 技能包系统自动配置。
 *
 * <p>提供 {@link SkillPackageManager} Bean 并从配置属性加载预定义技能包。
 * 通过 {@code skill-package.enabled=true} 启用（默认开启）。
 */
@Configuration
@EnableConfigurationProperties(SkillPackageProperties.class)
@ConditionalOnProperty(name = "skill-package.enabled", havingValue = "true", matchIfMissing = true)
public class SkillPackageAutoConfiguration implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SkillPackageAutoConfiguration.class);

    @Bean
    public SkillPackageManager skillPackageManager() {
        return new SkillPackageManager();
    }

    /**
     * 从配置属性加载技能包到管理器。
     */
    @Bean
    public InitializingBean skillPackageLoader(
            SkillPackageManager manager,
            SkillPackageProperties properties) {
        return () -> {
            properties.registerTo(manager);
            log.info("[SkillPackage] 技能包系统初始化完成，共 {} 个包",
                    manager.getAll().size());
        };
    }

    @Override
    public void afterPropertiesSet() {
        log.info("[SkillPackage] 技能包自动配置已启用");
    }
}
