/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.audit.AiAuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token 用量监测 Advisor — 真实采集 LLM 调用的 token 用量、模型与耗时，
 * 并发布结构化 {@link AiAuditEvent} 落库，补齐调用级可观测闭环。
 * <p>
 * 与 {@code PromptAuditAdvisor}（审计 prompt 文本）分工：本 Advisor 负责
 * <b>量化指标采集与审计事件发布</b>，日志仅保留链路级 DEBUG 摘要。
 * </p>
 * <p>
 * requestId/tenantId 来自 Slf4j MDC，由 {@code DistributedTracingService} 在请求入口设置，
 * 用于串联多请求并发时的调用链路。
 * </p>
 */
public class TokenUsageAdvisor implements CallAdvisor, StreamAdvisor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageAdvisor.class);

    /** 事件发布器；为 null 时不发布事件（保持无参构造向后兼容） */
    private final ApplicationEventPublisher eventPublisher;

    public TokenUsageAdvisor() {
        this(null);
    }

    public TokenUsageAdvisor(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** 从 MDC 获取请求追踪 ID，不存在时返回 "-" */
    private static String traceId() {
        String rid = MDC.get("requestId");
        if (rid != null && !rid.isBlank()) return rid;
        String trace = MDC.get("traceId");
        return trace != null && !trace.isBlank() ? trace : "-";
    }

    /** 从 MDC 获取租户 ID，不存在时返回 "-" */
    private static String tenantId() {
        String t = MDC.get("tenantId");
        return (t != null && !t.isBlank()) ? t : "-";
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        String tid = traceId();
        ChatClientResponse response = chain.nextCall(request);
        long latency = System.currentTimeMillis() - start;
        ChatResponseMetadata meta = (response.chatResponse() != null) ? response.chatResponse().getMetadata() : null;
        publishAudit(request, meta, latency, tid, "SUCCESS");
        log.debug("[TokenUsage][requestId={}] 调用完成 耗时={}ms", tid, latency);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.currentTimeMillis();
        String tid = traceId();
        AtomicReference<ChatResponseMetadata> lastMeta = new AtomicReference<>();
        return chain.nextStream(request)
                .doOnNext(r -> {
                    if (r.chatResponse() != null) {
                        lastMeta.set(r.chatResponse().getMetadata());
                    }
                })
                .doOnComplete(() -> publishAudit(request, lastMeta.get(),
                        System.currentTimeMillis() - start, tid, "SUCCESS"))
                .doOnError((Throwable ex) -> publishAudit(request, lastMeta.get(),
                        System.currentTimeMillis() - start, tid, "ERROR"));
    }

    /** 抽取用量并发布审计事件（eventPublisher 为 null 时跳过） */
    private void publishAudit(ChatClientRequest request, ChatResponseMetadata meta,
                              long latency, String tid, String resultType) {
        if (eventPublisher == null) return;

        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        String model = "-";
        if (meta != null) {
            Usage usage = meta.getUsage();
            if (usage != null) {
                if (usage.getPromptTokens() != null) promptTokens = usage.getPromptTokens();
                if (usage.getCompletionTokens() != null) completionTokens = usage.getCompletionTokens();
                if (usage.getTotalTokens() != null) totalTokens = usage.getTotalTokens();
            }
            if (meta.getModel() != null) model = meta.getModel();
        }
        String provider = resolveProvider(model);
        String digest = truncate(request.prompt() != null ? request.prompt().getContents() : "", 200);
        eventPublisher.publishEvent(new AiAuditEvent(
                tid, tenantId(), provider, model,
                promptTokens, completionTokens, totalTokens,
                latency, resultType, false, digest, Instant.now()));
    }

    /** 由模型名推断供应商（仅用于审计归类，不做路由） */
    private static String resolveProvider(String model) {
        if (model == null) return "-";
        String m = model.toLowerCase();
        if (m.contains("deepseek")) return "deepseek";
        if (m.contains("qwen") || m.contains("tongyi") || m.contains("dashscope")) return "alibaba";
        if (m.contains("gpt") || m.contains("openai")) return "openai";
        if (m.contains("claude")) return "anthropic";
        if (m.contains("gemini")) return "google";
        return "-";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @Override public String getName() { return "TokenUsageAdvisor"; }
    @Override public int getOrder() { return 350; }
}
