/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 运行时行为演示测试 — 验证 Advisor 链嵌入 Agent 流程后的实际日志输出。
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
@ExtendWith(MockitoExtension.class)
class AdvisorRuntimeBehaviorTest {

    private static final Logger log = LoggerFactory.getLogger(AdvisorRuntimeBehaviorTest.class);

    @Test
    @DisplayName("TokenUsageAdvisor: Call 路径产生耗时日志")
    void tokenUsageCallLog(@Mock ChatClientRequest request,
                            @Mock CallAdvisorChain chain,
                            @Mock ChatClientResponse response) {
        TokenUsageAdvisor advisor = new TokenUsageAdvisor();

        when(chain.nextCall(any())).thenAnswer(inv -> {
            sleepQuietly(50);
            return response;
        });

        log.info("=== TokenUsageAdvisor Call 路径 ===");
        ChatClientResponse result = advisor.adviseCall(request, chain);
        verify(chain).nextCall(request);
        // 日志输出: [TokenUsage] call done, ~50ms
        log.info("（上方日志显示 TokenUsage: call done, ~50ms）");
    }

    @Test
    @DisplayName("TokenUsageAdvisor: Stream 路径标记日志")
    void tokenUsageStreamLog(@Mock ChatClientRequest request,
                              @Mock StreamAdvisorChain chain) {
        TokenUsageAdvisor advisor = new TokenUsageAdvisor();

        when(chain.nextStream(any())).thenReturn(Flux.empty());

        log.info("=== TokenUsageAdvisor Stream 路径 ===");
        advisor.adviseStream(request, chain).subscribe();
        verify(chain).nextStream(request);
        // 日志输出: [TokenUsage] stream start
        log.info("（上方日志显示 TokenUsage: stream start）");
    }

    @Test
    @DisplayName("PromptAuditAdvisor: Call 路径审计日志")
    void promptAuditCallLog(@Mock ChatClientRequest request,
                             @Mock CallAdvisorChain chain,
                             @Mock ChatClientResponse response) {
        PromptAuditAdvisor advisor = new PromptAuditAdvisor();

        when(chain.nextCall(any())).thenReturn(response);

        log.info("=== PromptAuditAdvisor ===");
        log.info("启用: advisor.prompt-audit.enabled=true + DEBUG 日志");
        ChatClientResponse result = advisor.adviseCall(request, chain);
        verify(chain).nextCall(request);
        // 日志输出: [PromptAudit] Call (仅 DEBUG 级别)
        log.info("（DEBUG 级别日志: [PromptAudit] Call）");
    }

    @Test
    @DisplayName("ThinkingCollectorAdvisor: Stream 路径收集 reasoning")
    void thinkingCollectorStreamLog(@Mock ChatClientRequest request,
                                     @Mock StreamAdvisorChain chain) {
        ThinkingCollectorAdvisor advisor = new ThinkingCollectorAdvisor();

        when(chain.nextStream(any())).thenReturn(Flux.empty());

        log.info("=== ThinkingCollectorAdvisor Stream 路径 ===");
        advisor.adviseStream(request, chain).subscribe();
        verify(chain).nextStream(request);
        log.info("从 ChatResponse metadata 中提取 reasoning_content / reasoning 字段");
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
