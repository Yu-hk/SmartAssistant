/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.guardrail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性护栏配置属性。
 * 当用户输入命中 enforced-terms 或意图落入 enforced-intents 时，
 * 强制携带 RAG 检索上下文，绕过 LLM 路由决策避免误判。
 */
@Component
@ConfigurationProperties(prefix = "router.agent.rag.guardrails")
public class GuardrailProperties {

    /** 是否启用确定性护栏 */
    private boolean enabled = true;

    /**
     * 强制触发 RAG 的关键词列表（命中任意一个即触发）。
     * 建议配置：退款、退货、取消订单、发票、投诉、理赔、售后
     */
    private List<String> enforcedTerms = new ArrayList<>(List.of(
            "退款", "退货", "取消订单", "发票", "投诉", "理赔", "售后",
            "退钱", "赔偿", "申诉", "举报"
    ));

    /**
     * 强制触发 RAG 的意图标签列表。
     * 与 IntentFusionService 定义的 IntentType 保持一致。
     */
    private List<String> enforcedIntents = new ArrayList<>(List.of(
            "REFUND", "CANCEL", "COMPLAINT"
    ));

    /**
     * 护栏触发时是否跳过经验匹配和关键词快车道等短路路径。
     * 为 true 时保证走完整 LLM 路由路径 + RAG 增强。
     */
    private boolean skipShortCircuit = true;

    /**
     * 护栏触发时是否强制 RAG 增强（即使 router.agent.rag.enabled=false）。
     */
    private boolean forceRag = true;

    // ═══════════════════════════════════════════════════════════
    // Getters & Setters
    // ═══════════════════════════════════════════════════════════

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getEnforcedTerms() { return enforcedTerms; }
    public void setEnforcedTerms(List<String> enforcedTerms) { this.enforcedTerms = enforcedTerms; }

    public List<String> getEnforcedIntents() { return enforcedIntents; }
    public void setEnforcedIntents(List<String> enforcedIntents) { this.enforcedIntents = enforcedIntents; }

    public boolean isSkipShortCircuit() { return skipShortCircuit; }
    public void setSkipShortCircuit(boolean skipShortCircuit) { this.skipShortCircuit = skipShortCircuit; }

    public boolean isForceRag() { return forceRag; }
    public void setForceRag(boolean forceRag) { this.forceRag = forceRag; }
}
