/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.compliance;

import com.example.smartassistant.common.rag.properties.RagProductionProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 生成后合规校验自动装配（REQ-3）。
 *
 * <p>自包含创建合规链路 Bean，无需业务模块手动接线：
 * <ol>
 *   <li>{@link ComplianceRuleSet}：从 classpath {@code rag/compliance-rules.json} 加载（≥20 条）；</li>
 *   <li>{@link ComplianceGrader}：规则评分（可选 LLM 预留）；</li>
 *   <li>{@link ComplianceAuditRecorder}：审计落库（PG 优先，内存兜底）；</li>
 *   <li>{@link ComplianceGuard}：处置中枢（rewrite / block / warn + 审计）；</li>
 *   <li>{@link com.example.smartassistant.common.rag.advisor.PostGenerationComplianceAdvisor}：
 *        Advisor 链接入点（受 {@code app.compliance.enabled} 控制）。</li>
 * </ol>
 * </p>
 *
 * <p>同时通过 {@link EnableConfigurationProperties} 注册 {@link RagProductionProperties}，
 * 修复摄入管线的同类型 Bean 缺失问题（{@code IngestionJobAutoConfiguration} 注入该属性）。</p>
 */
@Configuration
@EnableConfigurationProperties(RagProductionProperties.class)
public class ComplianceAutoConfiguration {

    /** 规则集（classpath 资源 rag/compliance-rules.json） */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public ComplianceRuleSet complianceRuleSet() {
        return ComplianceRuleSet.fromClasspath();
    }

    /** 评分器（默认关闭 LLM，纯规则，避免误杀与外部依赖） */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public ComplianceGrader complianceGrader(ComplianceRuleSet ruleSet,
                                              RagProductionProperties properties) {
        boolean llm = properties != null && properties.getCompliance().isLlmEnabled();
        return new ComplianceGrader(ruleSet, llm);
    }

    /** 审计记录器（有 JdbcTemplate 落 PG，否则内存） */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public ComplianceAuditRecorder complianceAuditRecorder(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        return new ComplianceAuditRecorder(jdbcTemplateProvider.getIfAvailable());
    }

    /** 处置中枢（组合 grader + recorder + 全局默认策略） */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public ComplianceGuard complianceGuard(ComplianceGrader grader,
                                            ComplianceAuditRecorder recorder,
                                            RagProductionProperties properties) {
        String defaultStrategy = properties != null ? properties.getCompliance().getDefaultStrategy() : "rewrite";
        boolean enabled = properties == null || properties.getCompliance().isEnabled();
        return new ComplianceGuard(grader, recorder, defaultStrategy, enabled);
    }

    /** 生成后合规 Advisor（受 app.compliance.enabled 控制，默认开启） */
    @Bean
    @ConditionalOnProperty(name = "app.compliance.enabled", havingValue = "true", matchIfMissing = true)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public com.example.smartassistant.common.rag.advisor.PostGenerationComplianceAdvisor postGenerationComplianceAdvisor(
            ComplianceGuard guard) {
        return new com.example.smartassistant.common.rag.advisor.PostGenerationComplianceAdvisor(guard);
    }
}
