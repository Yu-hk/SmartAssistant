/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SmartReActAgent 的 G1（同工具同参数去重）与 G2（入口画像分级）验证。
 *
 * <p>通过 Mockito 驱动 ReAct 循环：mock ChatModel 每次返回含单个工具调用的
 * ChatResponse，工具名固定、参数由计数区分。因工具未在 toolMap 中注册，执行时
 * 返回 UNKNOWN_TOOL 错误后继续循环——此路径不额外调用 ChatModel，故模型调用次数
 * 可精确反映循环轮数。</p>
 */
@ExtendWith(MockitoExtension.class)
class SmartReActAgentGradingTest {

    @Mock
    private ChatModel chatModel;

    /** 构造仅含单个工具调用的 ChatResponse（工具名固定，参数由 args 区分）。 */
    private ChatResponse toolCallResponse(String toolName, String args) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call_1", "function", toolName, args);
        AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(call));
        return new ChatResponse(List.of(new Generation(msg)));
    }

    @Test
    @DisplayName("G1 同工具同参数重复调用 → 在 NO_INCREMENT_LIMIT 内停止（model 调用 3 次）")
    void g1_sameArgs_stopsEarly() {
        // 每次都返回完全相同的工具调用（同名称 + 同参数）
        when(chatModel.call(any(Prompt.class))).thenReturn(toolCallResponse("dummyTool", "{\"q\":1}"));

        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withCompress(false, 1000, 3);

        String result = agent.execute("查询一下", "sys", List.of());

        // 第1次建哈希；第2次 noIncrement=1；第3次 noIncrement=2 触发停止 → 共 3 次模型调用
        verify(chatModel, times(3)).call(any(Prompt.class));
        assertNotNull(result);
    }

    @Test
    @DisplayName("G1 参数每次不同 → 不触发无增量保护，跑满默认 maxIterations=10（model 调用 10 次）")
    void g1_diffArgs_runsToMax() {
        final int[] n = {0};
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            int i = ++n[0];
            return toolCallResponse("dummyTool", "{\"q\":" + i + "}");
        });

        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withCompress(false, 1000, 3);

        agent.execute("查询一下", "sys", List.of());

        // 参数每次不同 → 无增量检测永不触发 → 跑满默认 10 轮
        verify(chatModel, times(10)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("G2 入口画像 maxIterations=4 生效（model 调用 4 次）")
    void g2_profileMaxIterations_enforced() {
        final int[] n = {0};
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            int i = ++n[0];
            return toolCallResponse("dummyTool", "{\"q\":" + i + "}");
        });

        ReActProfile profile = new ReActProfile(4, 60_000, 0.8, 128_000, 30_000, 4);
        ReActProfileRegistry registry = new ReActProfileRegistry(Map.of("test", profile));

        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withProfile("test", registry)
                .withCompress(false, 1000, 3);

        agent.execute("查询一下", "sys", List.of());

        verify(chatModel, times(4)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("G2 入口画像缺失 key → 回退 DEFAULT（maxIterations=10，model 调用 10 次）")
    void g2_missingKey_fallsBackToDefault() {
        final int[] n = {0};
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            int i = ++n[0];
            return toolCallResponse("dummyTool", "{\"q\":" + i + "}");
        });

        // registry 不含 "absent" key
        ReActProfileRegistry registry = new ReActProfileRegistry(Map.of("other", ReActProfile.DEFAULT));

        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withProfile("absent", registry)
                .withCompress(false, 1000, 3);

        agent.execute("查询一下", "sys", List.of());

        verify(chatModel, times(10)).call(any(Prompt.class));
    }
}
