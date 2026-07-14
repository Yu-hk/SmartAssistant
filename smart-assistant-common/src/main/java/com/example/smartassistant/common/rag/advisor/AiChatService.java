/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 统一 ChatClient 工厂 — 对标 Spring AI 2.0 工程化样本中的 {@code AiChatService}。
 *
 * <p>收敛各业务模块重复的 ChatClient 构建样板：将 SafeGuard / TokenUsage / ThinkingCollector /
 * PromptAudit 四层 Advisor 的装配逻辑集中到一处。业务模块只需注入本服务并调用
 * {@link #buildChatClient(ChatModel)} 即可获得已装配完整 Advisor 链的 ChatClient，
 * 无需在各自的 {@code *AgentConfig} 中手动列举并 defaultAdvisors(...)。</p>
 *
 * <p>各 Advisor 均为可选（按 {@code advisor.*.enabled} 配置注入，缺失则为 null 并跳过），
 * 保证未启用某项时不影响构建。</p>
 */
public class AiChatService {

    private final SafeGuardAdvisor safeGuardAdvisor;
    private final TokenUsageAdvisor tokenUsageAdvisor;
    private final ThinkingCollectorAdvisor thinkingCollectorAdvisor;
    private final PromptAuditAdvisor promptAuditAdvisor;
    private final PostGenerationComplianceAdvisor postGenerationComplianceAdvisor;

    /**
     * 全参构造（含生成后合规 Advisor）。由 {@code AdvisorChainAutoConfiguration} 使用。
     */
    public AiChatService(
            SafeGuardAdvisor safeGuardAdvisor,
            TokenUsageAdvisor tokenUsageAdvisor,
            ThinkingCollectorAdvisor thinkingCollectorAdvisor,
            PromptAuditAdvisor promptAuditAdvisor,
            PostGenerationComplianceAdvisor postGenerationComplianceAdvisor) {
        this.safeGuardAdvisor = safeGuardAdvisor;
        this.tokenUsageAdvisor = tokenUsageAdvisor;
        this.thinkingCollectorAdvisor = thinkingCollectorAdvisor;
        this.promptAuditAdvisor = promptAuditAdvisor;
        this.postGenerationComplianceAdvisor = postGenerationComplianceAdvisor;
    }

    /**
     * 向后兼容构造（不含合规 Advisor）。测试与旧调用点使用。
     */
    public AiChatService(
            SafeGuardAdvisor safeGuardAdvisor,
            TokenUsageAdvisor tokenUsageAdvisor,
            ThinkingCollectorAdvisor thinkingCollectorAdvisor,
            PromptAuditAdvisor promptAuditAdvisor) {
        this(safeGuardAdvisor, tokenUsageAdvisor, thinkingCollectorAdvisor,
                promptAuditAdvisor, null);
    }

    /** 构建装配了完整 Advisor 链的 ChatClient */
    public ChatClient buildChatClient(ChatModel chatModel) {
        return applyAdvisors(ChatClient.builder(chatModel)).build();
    }

    /** 将 Advisor 链应用到已有 Builder（便于业务模块附加工具 / 系统提示） */
    public ChatClient.Builder applyAdvisors(ChatClient.Builder builder) {
        if (safeGuardAdvisor != null) builder.defaultAdvisors(safeGuardAdvisor);
        if (tokenUsageAdvisor != null) builder.defaultAdvisors(tokenUsageAdvisor);
        if (thinkingCollectorAdvisor != null) builder.defaultAdvisors(thinkingCollectorAdvisor);
        if (promptAuditAdvisor != null) builder.defaultAdvisors(promptAuditAdvisor);
        if (postGenerationComplianceAdvisor != null) builder.defaultAdvisors(postGenerationComplianceAdvisor);
        return builder;
    }

    /**
     * 结构化输出封装 — 对标工程化样本中的 {@code entity()} 用法。
     *
     * <p>将「用户文本 → 结构化对象」收敛为一行调用，且复用统一 Advisor 链
     * （安全护栏 / Token 审计 / 思考收集 / 提示审计）。业务侧无需再手动
     * {@code ChatClient.create(model)} 并拼接样板。</p>
     *
     * @param chatModel 聊天模型（通常注入 {@code lightChatModel} 等轻量模型）
     * @param userText  用户文本
     * @param type      目标结构化类型（推荐用 {@code record} 包装枚举 / 字段，
     *                  例如 {@code record IntentResult(IntentType intent){}}
     * @param <T>       目标类型
     * @return 结构化结果
     */
    public <T> T entity(ChatModel chatModel, String userText, Class<T> type) {
        return buildChatClient(chatModel).prompt().user(userText).call().entity(type);
    }

    /**
     * 带系统提示的结构化输出封装。适用于需要约束输出 schema / 角色设定的场景。
     *
     * @param chatModel  聊天模型
     * @param systemText 系统提示（角色 / 输出约束）
     * @param userText   用户文本
     * @param type       目标结构化类型
     * @param <T>        目标类型
     * @return 结构化结果
     */
    public <T> T entity(ChatModel chatModel, String systemText, String userText, Class<T> type) {
        return buildChatClient(chatModel).prompt().system(systemText).user(userText).call().entity(type);
    }
}
