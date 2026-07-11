/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.agent.AgentSafetyService;
import com.example.smartassistant.common.audit.AiAuditEvent;
import com.example.smartassistant.common.error.PromptInjectionBlockedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.time.Instant;

/**
 * 内容安全护栏 Advisor — 在请求进入模型前拦截 Prompt 注入攻击。
 *
 * <p>对标 Spring AI 2.0 工程化样本中的 {@code SafeGuardAdvisor}。与仅做日志审计的
 * {@code PromptAuditAdvisor} 分工明确：本 Advisor 负责<b>拦截</b>，命中即终止请求并
 * 发布 {@code BLOCKED} 审计事件；{@code PromptAuditAdvisor} 负责<b>记录</b>。</p>
 *
 * <p>检测能力复用 {@link AgentSafetyService#detectInjection(String)}（关键词 + 正则双检），
 * 仅针对明确的越权注入模式，避免误杀正常业务对话。命中后：
 * <ul>
 *   <li>输出 WARN 日志（含 requestId）</li>
 *   <li>发布 {@code AiAuditEvent(resultType=BLOCKED)} 落库</li>
 *   <li>抛出 {@code PromptInjectionBlockedException}，由 {@code SmartReActAgent} 捕获并返回友好提示</li>
 * </ul>
 *
 * <p>Order=50，置于所有 Advisor 之前（PromptAudit=100 / TokenUsage=350 / ThinkingCollector=400），
 * 确保有害输入在最前置被拦截。</p>
 */
public class SafeGuardAdvisor implements CallAdvisor, StreamAdvisor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SafeGuardAdvisor.class);

    private final AgentSafetyService safetyService;
    private final ApplicationEventPublisher eventPublisher;

    public SafeGuardAdvisor() {
        this(null);
    }

    public SafeGuardAdvisor(ApplicationEventPublisher eventPublisher) {
        this(new AgentSafetyService(), eventPublisher);
    }

    public SafeGuardAdvisor(AgentSafetyService safetyService, ApplicationEventPublisher eventPublisher) {
        this.safetyService = safetyService != null ? safetyService : new AgentSafetyService();
        this.eventPublisher = eventPublisher;
    }

    private static String traceId() {
        String rid = MDC.get("requestId");
        if (rid != null && !rid.isBlank()) return rid;
        String trace = MDC.get("traceId");
        return trace != null && !trace.isBlank() ? trace : "-";
    }

    private static String tenantId() {
        String t = MDC.get("tenantId");
        return (t != null && !t.isBlank()) ? t : "-";
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        AgentSafetyService.InjectionResult result = safetyService.detectInjection(scanText(request));
        if (!result.isSafe()) {
            log.warn("[SafeGuard][requestId={}] 拦截注入输入: {}", traceId(), result.reason());
            publishBlocked(request);
            throw new PromptInjectionBlockedException(result.reason());
        }
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        AgentSafetyService.InjectionResult result = safetyService.detectInjection(scanText(request));
        if (!result.isSafe()) {
            log.warn("[SafeGuard][requestId={}] 拦截注入输入: {}", traceId(), result.reason());
            publishBlocked(request);
            return Flux.error(new PromptInjectionBlockedException(result.reason()));
        }
        return chain.nextStream(request);
    }

    /** 提取待扫描文本（prompt 合并文本） */
    private String scanText(ChatClientRequest request) {
        if (request.prompt() == null) return "";
        String contents = request.prompt().getContents();
        return contents != null ? contents : "";
    }

    private void publishBlocked(ChatClientRequest request) {
        if (eventPublisher == null) return;
        String digest = truncate(request.prompt() != null ? request.prompt().getContents() : "", 200);
        eventPublisher.publishEvent(new AiAuditEvent(
                traceId(), tenantId(), "-", "-",
                0, 0, 0, 0, "BLOCKED", false, digest, Instant.now()));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @Override public String getName() { return "SafeGuardAdvisor"; }
    @Override public int getOrder() { return 50; }
}
