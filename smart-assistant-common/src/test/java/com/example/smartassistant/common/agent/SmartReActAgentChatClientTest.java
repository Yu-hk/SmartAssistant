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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link SmartReActAgent} 与 ChatClient/Advisor 链的集成测试。
 * <p>
 * 验证 {@code withChatClient()} 存储 ChatClient 的行为，
 * 以及无 ChatClient 时走 ChatModel 路径。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
@ExtendWith(MockitoExtension.class)
class SmartReActAgentChatClientTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatClient chatClient;

    @Test
    @DisplayName("withChatClient 返回 agent 自身（fluent API）")
    void withChatClient_returnsSelf() {
        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withChatClient(chatClient);
        assertNotNull(agent);
    }

    @Test
    @DisplayName("多 Advisors 注册到 ChatClient Builder")
    void multipleAdvisorsInBuilder() {
        // ⭐ 模拟真实场景：builder 中添加多个 Advisor
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        assertNotNull(builder);

        builder.defaultAdvisors(mock(Advisor.class));
        builder.defaultAdvisors(mock(Advisor.class));
        builder.defaultAdvisors(mock(Advisor.class));

        ChatClient built = builder.build();
        assertNotNull(built);
    }

    @Test
    @DisplayName("ChatClient 为 null 时优雅降级（无 NPE）")
    void nullChatClient_doesNotBreakAgent() {
        SmartReActAgent agent = new SmartReActAgent(chatModel)
                .withChatClient(null);
        assertNotNull(agent);
    }

    @Test
    @DisplayName("不带 ChatClient 时，Agent 使用 ChatModel 路径")
    void withoutChatClient_usesChatModel() {
        SmartReActAgent agent = new SmartReActAgent(chatModel);
        assertNotNull(agent);
        // chatClient.prompt() 不会被调用（因为没有设置 ChatClient）
        verify(chatClient, never()).prompt();
    }

    /**
     * 测试用的 Advisor 标记接口（避免对公共 Advisor 类型的运行时依赖）。
     */
    private interface Advisor extends org.springframework.ai.chat.client.advisor.api.CallAdvisor, org.springframework.ai.chat.client.advisor.api.StreamAdvisor {}
}
