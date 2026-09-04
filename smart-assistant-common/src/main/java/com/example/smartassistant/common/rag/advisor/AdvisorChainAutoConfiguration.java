/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.audit.AiAuditEvent;
import com.example.smartassistant.common.governance.CallLimitProperties;
import com.example.smartassistant.common.governance.GovernanceInfrastructureConfiguration;
import com.example.smartassistant.common.governance.InvocationBudgetRegistry;
import com.example.smartassistant.common.security.PiiPolicyConfiguration;
import com.example.smartassistant.common.security.PiiPolicyEngine;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Spring AI Advisor 链自动配置。
 *
 * <p>注册 Advisor：
 * <ol>
 *   <li><b>PromptAuditAdvisor</b> (Order=100) — 请求/响应审计日志</li>
 *   <li><b>TokenUsageAdvisor</b> (Order=350) — Token 用量采集 + 审计事件发布</li>
 * </ol>
 *
 * <p>此外注册 {@link #onAiAudit(AiAuditEvent)} 事件监听器，将每次 LLM 调用的
 * 结构化指标写入统一日志；持久化遥测由 Micrometer Observation 后端负责。</p>
 *
 * <p>设置 ChatClient 时通过 {@code .defaultAdvisors(advisors...)} 注册即可生效。
 * 各模块可通过 {@code advisor.*.enabled=false} 按需关闭。</p>
 */
@AutoConfiguration(after = {
        PiiPolicyConfiguration.class,
        GovernanceInfrastructureConfiguration.class
})
public class AdvisorChainAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdvisorChainAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SafeGuardAdvisor.class)
    @ConditionalOnProperty(name = "advisor.safe-guard.enabled", havingValue = "true", matchIfMissing = true)
    public SafeGuardAdvisor safeGuardAdvisor(ApplicationEventPublisher publisher) {
        log.info("[AdvisorChain] 注册 SafeGuardAdvisor (Order=50, 内容安全护栏)");
        return new SafeGuardAdvisor(publisher);
    }

    @Bean
    @ConditionalOnMissingBean(PromptAuditAdvisor.class)
    @ConditionalOnProperty(name = "advisor.prompt-audit.enabled", havingValue = "true")
    public PromptAuditAdvisor promptAuditAdvisor(PiiPolicyEngine piiPolicyEngine) {
        log.info("[AdvisorChain] 注册 PromptAuditAdvisor (Order=100)");
        return new PromptAuditAdvisor(piiPolicyEngine);
    }

    @Bean
    @ConditionalOnMissingBean(PiiAdvisor.class)
    public PiiAdvisor piiAdvisor(PiiPolicyEngine engine) {
        return new PiiAdvisor(engine);
    }

    @Bean
    @ConditionalOnMissingBean(ModelCallLimitAdvisor.class)
    public ModelCallLimitAdvisor modelCallLimitAdvisor(InvocationBudgetRegistry registry,
                                                        CallLimitProperties properties) {
        return new ModelCallLimitAdvisor(registry, properties);
    }

    /** Mandatory policies for every Spring Boot managed ChatClient.Builder. */
    @Bean
    @ConditionalOnMissingBean(name = "governanceChatClientBuilderCustomizer")
    public ChatClientBuilderCustomizer governanceChatClientBuilderCustomizer(
            PiiAdvisor piiAdvisor, ModelCallLimitAdvisor modelCallLimitAdvisor) {
        return builder -> builder.defaultAdvisors(piiAdvisor, modelCallLimitAdvisor);
    }

    @Bean
    @ConditionalOnMissingBean(TokenUsageAdvisor.class)
    @ConditionalOnProperty(name = "advisor.token-usage.enabled", havingValue = "true", matchIfMissing = true)
    public TokenUsageAdvisor tokenUsageAdvisor(ApplicationEventPublisher publisher) {
        log.info("[AdvisorChain] 注册 TokenUsageAdvisor (Order=350, 含审计事件发布)");
        return new TokenUsageAdvisor(publisher);
    }

    /**
     * 接收 TokenUsageAdvisor / SafeGuardAdvisor 发布的审计事件并输出结构化审计日志。
     */
    @EventListener(AiAuditEvent.class)
    public void onAiAudit(AiAuditEvent event) {
        log.info("[AiAudit][requestId={}] provider={} model={} tokens(p/c/t)={}/{}/{} latency={}ms type={} approved={}",
                event.traceId(), event.provider(), event.model(),
                event.promptTokens(), event.completionTokens(), event.totalTokens(),
                event.latencyMs(), event.resultType(), event.approved());
    }

    /**
     * 统一 ChatClient 工厂 — 装配完整 Advisor 链（SafeGuard/TokenUsage/PromptAudit）。
     * 由各业务模块的 AgentConfig 注入后调用 {@code buildChatClient(chatModel)} 获取现成 ChatClient。
     */
    @Bean
    @ConditionalOnMissingBean(AiChatService.class)
    public AiChatService aiChatService(
            @org.springframework.beans.factory.annotation.Autowired(required = false) SafeGuardAdvisor safeGuardAdvisor,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TokenUsageAdvisor tokenUsageAdvisor,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PromptAuditAdvisor promptAuditAdvisor,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PostGenerationComplianceAdvisor postGenerationComplianceAdvisor,
            ModelCallLimitAdvisor modelCallLimitAdvisor,
            PiiAdvisor piiAdvisor,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ToolCallingManager> toolCallingManagerProvider) {
        return new AiChatService(safeGuardAdvisor, tokenUsageAdvisor,
                promptAuditAdvisor, postGenerationComplianceAdvisor, modelCallLimitAdvisor, piiAdvisor,
                observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP),
                toolCallingManagerProvider.getIfAvailable());
    }

}
