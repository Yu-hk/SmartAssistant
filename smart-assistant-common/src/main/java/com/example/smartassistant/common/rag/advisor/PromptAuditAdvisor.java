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
 * Prompt 审计 Advisor — 在 DEBUG 级别记录 Prompt 调用审计信息，附带请求追踪上下文。
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
        super(request -> requestToString(request, engine),
                response -> responseToString(response, engine), 100);
    }

    /** 从 MDC 获取请求追踪 ID */
    private static String traceId() {
        String rid = MDC.get("requestId");
        if (rid != null && !rid.isBlank()) return rid;
        String trace = MDC.get("traceId");
        return trace != null && !trace.isBlank() ? trace : "-";
    }

    private static String requestToString(ChatClientRequest request, PiiPolicyEngine engine) {
        String promptText = request != null && request.prompt() != null
                ? request.prompt().getContents() : "";
        return "[PromptAudit][requestId=" + traceId() + "] request prompt="
                + truncate(engine.sanitize(promptText), 120);
    }

    private static String responseToString(ChatResponse response, PiiPolicyEngine engine) {
        String responseText = response != null ? response.toString() : "";
        return "[PromptAudit][requestId=" + traceId() + "] response="
                + truncate(engine.sanitize(responseText), 120);
    }

    @Override
    public String getName() {
        return "PromptAuditAdvisor";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
