/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.rag.compliance.ComplianceGuard;
import com.example.smartassistant.common.rag.compliance.ComplianceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 生成后合规 Advisor（REQ-3）——在模型生成之后、返回用户之前对回答做合规校验与改写。
 *
 * <p>置于 Advisor 链末段（{@code Order=450}，紧贴模型输出），由
 * {@link ComplianceGuard#check(String)} 完成规则命中 → 改写 / 拒答 / 告警的处置：
 * <ul>
 *   <li><b>改写（rewrite）</b>：用改写后文本重建 {@link ChatClientResponse} 放行；</li>
 *   <li><b>拒答（block）</b>：以安全模板替换正文；</li>
 *   <li><b>告警（warn）</b>：原文放行，仅写审计。</li>
 * </ul>
 * </p>
 *
 * <p><b>流式说明</b>：当前业务链路（router / product）均走 {@code .call()} 同步路径，本 Advisor
 * 在 {@link #adviseCall} 中完成完整合规处置。{@link #adviseStream} 直接透传
 * （{@code chain.nextStream(request)}），保证既有流式契约零变更；如后续启用流式生成，
 * 可在此聚合分片后再做合规改写。</p>
 */
public class PostGenerationComplianceAdvisor implements CallAdvisor, StreamAdvisor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(PostGenerationComplianceAdvisor.class);

    private final ComplianceGuard guard;

    public PostGenerationComplianceAdvisor(ComplianceGuard guard) {
        this.guard = guard;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        return rewriteResponse(response);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // 透传：同步 call 路径已完成合规处置，流式契约保持不变。
        return chain.nextStream(request);
    }

    /**
     * 从响应中提取助手文本。
     */
    private String extractText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) return "";
        Generation result = response.chatResponse().getResult();
        if (result == null) return "";
        AssistantMessage message = result.getOutput();
        return message != null ? message.getText() : "";
    }

    /**
     * 对响应做合规校验并按结果重建返回。未命中或无需改写时原样返回（零开销）。
     */
    private ChatClientResponse rewriteResponse(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return response;
        }
        String text = extractText(response);
        if (text == null || text.isBlank()) {
            return response;
        }
        ComplianceResult result = guard.check(text);
        String out = result.getOutput();
        if (out == null || out.equals(text)) {
            return response; // 未命中 / 仅告警：原样返回
        }

        // 重建 ChatClientResponse（保留原 metadata 与 context）
        org.springframework.ai.chat.metadata.ChatResponseMetadata metadata =
                response.chatResponse().getMetadata();
        ChatResponse newChatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(out))), metadata);
        Map<String, Object> ctx = response.context() != null
                ? response.context() : Collections.emptyMap();
        log.debug("[PostGenCompliance] 已改写回答: strategy={}", result.getStrategyApplied());
        return ChatClientResponse.builder()
                .chatResponse(newChatResponse)
                .context(ctx)
                .build();
    }

    @Override
    public String getName() { return "PostGenerationComplianceAdvisor"; }

    /** 紧贴模型输出（post-generation），位于其它 Advisor 之后 */
    @Override
    public int getOrder() { return 450; }
}
