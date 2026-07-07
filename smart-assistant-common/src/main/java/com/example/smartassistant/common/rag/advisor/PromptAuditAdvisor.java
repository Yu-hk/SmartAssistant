/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Prompt 审计 Advisor — 在 DEBUG 级别记录 Prompt 调用审计信息，附带请求追踪上下文。
 * <p>
 * 默认关闭，需配置 {@code advisor.prompt-audit.enabled=true}。
 * 日志格式包含 {@code [requestId=xxx]} 以区分多请求并发时的调用链路。
 * </p>
 */
public class PromptAuditAdvisor implements CallAdvisor, StreamAdvisor, Ordered {
    private static final Logger log = LoggerFactory.getLogger(PromptAuditAdvisor.class);

    /** 从 MDC 获取请求追踪 ID */
    private static String traceId() {
        String rid = MDC.get("requestId");
        if (rid != null && !rid.isBlank()) return rid;
        String trace = MDC.get("traceId");
        return trace != null && !trace.isBlank() ? trace : "-";
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (log.isDebugEnabled()) {
            String promptText = request.prompt() != null ? request.prompt().getContents() : "";
            log.debug("[PromptAudit][requestId={}] Call — prompt={}", traceId(),
                    truncate(promptText, 120));
        }
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (log.isDebugEnabled()) {
            String promptText = request.prompt() != null ? request.prompt().getContents() : "";
            log.debug("[PromptAudit][requestId={}] Stream — prompt={}", traceId(),
                    truncate(promptText, 120));
        }
        return chain.nextStream(request);
    }

    @Override public String getName() { return "PromptAuditAdvisor"; }
    @Override public int getOrder() { return 100; }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
