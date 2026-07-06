/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Advisor 链自动配置。
 *
 * <p>注册三层 Advisor：
 * <ol>
 *   <li><b>PromptAuditAdvisor</b> (Order=100) — 请求/响应审计日志</li>
 *   <li><b>TokenUsageAdvisor</b> (Order=350) — Token 用量采集</li>
 *   <li><b>ThinkingCollectorAdvisor</b> (Order=400) — 思维链收集</li>
 * </ol>
 *
 * <p>设置 ChatClient 时通过 {@code .defaultAdvisors(advisors...)} 注册即可生效。
 *
 * <p>各模块可通过 {@code advisor.*.enabled=false} 按需关闭。
 */
@Configuration
public class AdvisorChainAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdvisorChainAutoConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "advisor.prompt-audit.enabled", havingValue = "true")
    public PromptAuditAdvisor promptAuditAdvisor() {
        log.info("[AdvisorChain] 注册 PromptAuditAdvisor (Order=100)");
        return new PromptAuditAdvisor();
    }

    @Bean
    @ConditionalOnProperty(name = "advisor.token-usage.enabled", havingValue = "true", matchIfMissing = true)
    public TokenUsageAdvisor tokenUsageAdvisor() {
        log.info("[AdvisorChain] 注册 TokenUsageAdvisor (Order=350)");
        return new TokenUsageAdvisor();
    }

    @Bean
    @ConditionalOnProperty(name = "advisor.thinking-collector.enabled", havingValue = "true", matchIfMissing = true)
    public ThinkingCollectorAdvisor thinkingCollectorAdvisor() {
        log.info("[AdvisorChain] 注册 ThinkingCollectorAdvisor (Order=400)");
        return new ThinkingCollectorAdvisor();
    }
}
