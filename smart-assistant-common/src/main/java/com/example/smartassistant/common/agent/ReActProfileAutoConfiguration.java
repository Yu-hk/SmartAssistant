/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 入口级 ReAct 画像自动装配。
 *
 * <p>从 {@link ReActProfileProperties} 构建 {@link ReActProfileRegistry}；
 * 缺失字段以 {@link ReActProfile#DEFAULT} 补齐，保证未配置任何入口时
 * 各 Agent 仍使用默认画像。</p>
 */
@Configuration
@EnableConfigurationProperties(ReActProfileProperties.class)
public class ReActProfileAutoConfiguration {

    @Bean
    public ReActProfileRegistry reactProfileRegistry(ReActProfileProperties properties) {
        Map<String, ReActProfile> map = new HashMap<>();
        if (properties.getProfiles() != null) {
            for (Map.Entry<String, ReActProfileProperties.ProfileConfig> e : properties.getProfiles().entrySet()) {
                ReActProfileProperties.ProfileConfig c = e.getValue();
                ReActProfile d = ReActProfile.DEFAULT;
                ReActProfile p = new ReActProfile(
                        c.getMaxIterations() != null ? c.getMaxIterations() : d.maxIterations(),
                        c.getTimeoutMs() != null ? c.getTimeoutMs() : d.timeoutMs(),
                        c.getTokenBudgetRatio() != null ? c.getTokenBudgetRatio() : d.tokenBudgetRatio(),
                        c.getContextWindow() != null ? c.getContextWindow() : d.contextWindow(),
                        c.getToolTimeoutMs() != null ? c.getToolTimeoutMs() : d.toolTimeoutMs(),
                        c.getMaxConcurrency() != null ? c.getMaxConcurrency() : d.maxConcurrency());
                map.put(e.getKey(), p);
            }
        }
        return new ReActProfileRegistry(map);
    }
}
