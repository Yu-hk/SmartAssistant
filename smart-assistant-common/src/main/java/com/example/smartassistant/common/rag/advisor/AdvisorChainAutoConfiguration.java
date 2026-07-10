/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.audit.AiAuditEvent;
import com.example.smartassistant.common.audit.AiAuditStore;
import com.example.smartassistant.common.tool.AiToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Advisor 链自动配置。
 *
 * <p>注册三层 Advisor：
 * <ol>
 *   <li><b>PromptAuditAdvisor</b> (Order=100) — 请求/响应审计日志</li>
 *   <li><b>TokenUsageAdvisor</b> (Order=350) — Token 用量采集 + 审计事件发布</li>
 *   <li><b>ThinkingCollectorAdvisor</b> (Order=400) — 思维链收集</li>
 * </ol>
 *
 * <p>此外注册 AI 审计基础设施：{@link AiAuditStore} 与 {@link #onAiAudit(AiAuditEvent)}
 * 事件监听器，将每次 LLM 调用的结构化指标落库。</p>
 *
 * <p>设置 ChatClient 时通过 {@code .defaultAdvisors(advisors...)} 注册即可生效。
 * 各模块可通过 {@code advisor.*.enabled=false} 按需关闭。</p>
 */
@Configuration
public class AdvisorChainAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdvisorChainAutoConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "advisor.safe-guard.enabled", havingValue = "true", matchIfMissing = true)
    public SafeGuardAdvisor safeGuardAdvisor(ApplicationEventPublisher publisher) {
        log.info("[AdvisorChain] 注册 SafeGuardAdvisor (Order=50, 内容安全护栏)");
        return new SafeGuardAdvisor(publisher);
    }

    @Bean
    @ConditionalOnProperty(name = "advisor.prompt-audit.enabled", havingValue = "true")
    public PromptAuditAdvisor promptAuditAdvisor() {
        log.info("[AdvisorChain] 注册 PromptAuditAdvisor (Order=100)");
        return new PromptAuditAdvisor();
    }

    @Bean
    @ConditionalOnProperty(name = "advisor.token-usage.enabled", havingValue = "true", matchIfMissing = true)
    public TokenUsageAdvisor tokenUsageAdvisor(ApplicationEventPublisher publisher) {
        log.info("[AdvisorChain] 注册 TokenUsageAdvisor (Order=350, 含审计事件发布)");
        return new TokenUsageAdvisor(publisher);
    }

    @Bean
    @ConditionalOnProperty(name = "advisor.thinking-collector.enabled", havingValue = "true", matchIfMissing = true)
    public ThinkingCollectorAdvisor thinkingCollectorAdvisor() {
        log.info("[AdvisorChain] 注册 ThinkingCollectorAdvisor (Order=400)");
        return new ThinkingCollectorAdvisor();
    }

    /** AI 审计事件存储（内存环形缓冲，默认保留最近 1000 条） */
    @Bean
    public AiAuditStore aiAuditStore() {
        return new AiAuditStore();
    }

    /**
     * 接收 TokenUsageAdvisor / SafeGuardAdvisor 发布的审计事件，落库并输出结构化审计日志。
     * 在 @Configuration CGLIB 代理下 {@link #aiAuditStore()} 返回容器中的单例，安全复用。
     */
    @EventListener(AiAuditEvent.class)
    public void onAiAudit(AiAuditEvent event) {
        aiAuditStore().add(event);
        log.info("[AiAudit][requestId={}] provider={} model={} tokens(p/c/t)={}/{}/{} latency={}ms type={} approved={}",
                event.traceId(), event.provider(), event.model(),
                event.promptTokens(), event.completionTokens(), event.totalTokens(),
                event.latencyMs(), event.resultType(), event.approved());
    }

    /**
     * 统一 ChatClient 工厂 — 装配完整 Advisor 链（SafeGuard/TokenUsage/ThinkingCollector/PromptAudit）。
     * 由各业务模块的 AgentConfig 注入后调用 {@code buildChatClient(chatModel)} 获取现成 ChatClient。
     */
    @Bean
    public AiChatService aiChatService(
            @org.springframework.beans.factory.annotation.Autowired(required = false) SafeGuardAdvisor safeGuardAdvisor,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TokenUsageAdvisor tokenUsageAdvisor,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ThinkingCollectorAdvisor thinkingCollectorAdvisor,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PromptAuditAdvisor promptAuditAdvisor) {
        return new AiChatService(safeGuardAdvisor, tokenUsageAdvisor, thinkingCollectorAdvisor, promptAuditAdvisor);
    }

    /** 工具注册聚合器 — 收敛工具对象 → ToolCallback 列表的构建样板 */
    @Bean
    public AiToolRegistry aiToolRegistry() {
        return new AiToolRegistry();
    }
}
