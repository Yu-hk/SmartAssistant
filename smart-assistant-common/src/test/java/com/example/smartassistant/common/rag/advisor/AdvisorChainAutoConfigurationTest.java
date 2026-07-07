/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdvisorChainAutoConfiguration} 集成测试 — 验证 3 个 Advisor Bean 的条件创建。
 * <p>
 * 测试覆盖：
 * <ul>
 *   <li>默认配置（matchIfMissing=true）→ TokenUsage + ThinkingCollector 存在，PromptAudit 不存在</li>
 *   <li>全部启用 → 3 个 Advisor 均存在</li>
 *   <li>全部关闭 → 3 个 Advisor 均不存在</li>
 * </ul>
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
class AdvisorChainAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AdvisorChainAutoConfiguration.class));

    @Test
    @DisplayName("默认配置：TokenUsage+ThinkingCollector 存在，PromptAudit 不存在")
    void defaultConfig() {
        contextRunner.run(ctx -> {
            assertThat(ctx.containsBean("tokenUsageAdvisor")).isTrue();
            assertThat(ctx.containsBean("thinkingCollectorAdvisor")).isTrue();
            assertThat(ctx.containsBean("promptAuditAdvisor")).isFalse();
        });
    }

    @Test
    @DisplayName("全部启用：3 个 Advisor 均存在")
    void allEnabled() {
        contextRunner
                .withPropertyValues(
                        "advisor.token-usage.enabled=true",
                        "advisor.thinking-collector.enabled=true",
                        "advisor.prompt-audit.enabled=true")
                .run(ctx -> {
                    assertThat(ctx.containsBean("tokenUsageAdvisor")).isTrue();
                    assertThat(ctx.containsBean("thinkingCollectorAdvisor")).isTrue();
                    assertThat(ctx.containsBean("promptAuditAdvisor")).isTrue();
                });
    }

    @Test
    @DisplayName("全部关闭：3 个 Advisor 均不存在")
    void allDisabled() {
        contextRunner
                .withPropertyValues(
                        "advisor.token-usage.enabled=false",
                        "advisor.thinking-collector.enabled=false",
                        "advisor.prompt-audit.enabled=false")
                .run(ctx -> {
                    assertThat(ctx.containsBean("tokenUsageAdvisor")).isFalse();
                    assertThat(ctx.containsBean("thinkingCollectorAdvisor")).isFalse();
                    assertThat(ctx.containsBean("promptAuditAdvisor")).isFalse();
                });
    }

    @Test
    @DisplayName("仅开启 PromptAudit：仅该 Bean 存在")
    void onlyPromptAuditEnabled() {
        contextRunner
                .withPropertyValues(
                        "advisor.token-usage.enabled=false",
                        "advisor.thinking-collector.enabled=false",
                        "advisor.prompt-audit.enabled=true")
                .run(ctx -> {
                    assertThat(ctx.containsBean("tokenUsageAdvisor")).isFalse();
                    assertThat(ctx.containsBean("thinkingCollectorAdvisor")).isFalse();
                    assertThat(ctx.containsBean("promptAuditAdvisor")).isTrue();
                });
    }

    @Test
    @DisplayName("Bean 类型正确：TokenUsage → TokenUsageAdvisor")
    void beanTypeTokenUsage() {
        contextRunner.run(ctx -> {
            assertThat(ctx.getBean("tokenUsageAdvisor"))
                    .isInstanceOf(TokenUsageAdvisor.class);
            assertThat(ctx.getBean("thinkingCollectorAdvisor"))
                    .isInstanceOf(ThinkingCollectorAdvisor.class);
        });
    }
}
