/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.rag.compliance.ComplianceAuditRecorder;
import com.example.smartassistant.common.rag.compliance.ComplianceGrader;
import com.example.smartassistant.common.rag.compliance.ComplianceGuard;
import com.example.smartassistant.common.rag.compliance.ComplianceRuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PostGenerationComplianceAdvisor 单元测试（REQ-3 接入生成末端）。
 * <p>验证模型生成输出经 Advisor 后：超承诺被改写、投资欺诈被 block 为安全模板。</p>
 */
@ExtendWith(MockitoExtension.class)
class PostGenerationComplianceAdvisorTest {

    @Mock
    private CallAdvisorChain chain;

    private PostGenerationComplianceAdvisor advisor() {
        ComplianceGuard guard = new ComplianceGuard(
                new ComplianceGrader(ComplianceRuleSet.fromClasspath()),
                new ComplianceAuditRecorder(), "rewrite", true);
        return new PostGenerationComplianceAdvisor(guard);
    }

    private ChatClientResponse responseWith(String text) {
        ChatResponse cr = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        return ChatClientResponse.builder().chatResponse(cr).build();
    }

    private String outputOf(ChatClientResponse resp) {
        return resp.chatResponse().getResult().getOutput().getText();
    }

    @Test
    void rewritesOverPromiseOutput() {
        when(chain.nextCall(any())).thenReturn(responseWith("您可以保证一定能订到房间"));

        ChatClientResponse result = advisor().adviseCall(mock(ChatClientRequest.class), chain);
        String out = outputOf(result);
        assertFalse(out.contains("一定能"), "Advisor 应在生成末端改写超承诺表述");
    }

    @Test
    void blocksFraudOutputWithSafeTemplate() {
        when(chain.nextCall(any())).thenReturn(responseWith("稳赚不赔的投资项目推荐给您"));

        ChatClientResponse result = advisor().adviseCall(mock(ChatClientRequest.class), chain);
        String out = outputOf(result);
        assertTrue(out.contains("无法对相关内容作出承诺"), "投资欺诈应被 block 为安全拒答模板");
    }

    @Test
    void benignOutputUnchanged() {
        when(chain.nextCall(any())).thenReturn(
                responseWith("您可以拨打人工客服电话咨询具体的退款政策与办理流程。"));

        ChatClientResponse result = advisor().adviseCall(mock(ChatClientRequest.class), chain);
        String out = outputOf(result);
        assertTrue(out.contains("人工客服电话"), "良性输出应原样返回，不被改写/拒答");
    }
}
