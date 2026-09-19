/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.security.PiiPolicyEngine;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Prompt 审计 Advisor — 仅记录调用元数据，不把提示词、模型回复或画像正文复制到日志。
 * <p>
 * 默认关闭，需配置 {@code advisor.prompt-audit.enabled=true}。
 * 日志格式包含 {@code [requestId=xxx]} 以区分多请求并发时的调用链路。
 * </p>
 */
public class PromptAuditAdvisor extends SimpleLoggerAdvisor {

    public PromptAuditAdvisor() {
        this(PiiPolicyEngine.shared());
    }

    public PromptAuditAdvisor(PiiPolicyEngine engine) {
        // Keep the constructor for source compatibility. PII regexes cannot prove
        // arbitrary profile prose is safe; do not stringify request/response bodies.
        super(PromptAuditAdvisor::requestToString, PromptAuditAdvisor::responseToString, 100);
    }

    /** 从 MDC 获取请求追踪 ID */
    private static String traceId() {
        String rid = MDC.get("requestId");
        if (rid != null && !rid.isBlank()) return safeTraceId(rid);
        String trace = MDC.get("traceId");
        return trace != null && !trace.isBlank() ? safeTraceId(trace) : "-";
    }

    static String requestToString(ChatClientRequest request) {
        int messages = request != null && request.prompt() != null
                ? request.prompt().getInstructions().size() : 0;
        return "[PromptAudit][requestId=" + traceId() + "] request messages=" + messages;
    }

    static String responseToString(ChatResponse response) {
        int generations = response != null && response.getResults() != null ? response.getResults().size() : 0;
        return "[PromptAudit][requestId=" + traceId() + "] response generations=" + generations;
    }

    @Override
    public String getName() {
        return "PromptAuditAdvisor";
    }

    private static String safeTraceId(String value) {
        return value.matches("[A-Za-z0-9_.:-]{1,128}") ? value : "invalid";
    }
}
