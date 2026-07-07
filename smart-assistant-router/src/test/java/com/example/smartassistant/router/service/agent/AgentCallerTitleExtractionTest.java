/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * AgentCallerService.extractTitles 结构化抽取测试（对标 OrderIntentService.entity() 约定）。
 */
@ExtendWith(MockitoExtension.class)
class AgentCallerTitleExtractionTest {

    @Mock
    AiChatService aiChatService;
    @Mock
    ChatModel lightModel;

    @Test
    @DisplayName("注入 AiChatService 时，应把 Agent 回复绑定为结构化标题/标签")
    void shouldExtractStructuredTitles() {
        AgentCallerService service = new AgentCallerService(
                null, null, aiChatService, lightModel);

        AgentCallerService.ExtractedTitles expected = new AgentCallerService.ExtractedTitles(
                List.of("故宫一日游攻略"), Map.of("故宫一日游攻略", "北京"));
        when(aiChatService.entity(any(ChatModel.class), anyString(), anyString(), eq(AgentCallerService.ExtractedTitles.class)))
                .thenReturn(expected);

        AgentCallerService.ExtractedTitles actual = service.extractTitles(
                "这是一篇关于故宫一日游攻略的游记，包含路线安排。");

        assertNotNull(actual);
        assertEquals(List.of("故宫一日游攻略"), actual.titles());
        assertEquals("北京", actual.tagsByTitle().get("故宫一日游攻略"));
    }

    @Test
    @DisplayName("未注入 AiChatService（3 参构造）时降级为 EMPTY，不抛异常")
    void shouldFallbackWhenNoAiChatService() {
        AgentCallerService service = new AgentCallerService(null, null, null);
        AgentCallerService.ExtractedTitles actual = service.extractTitles("任意回复");
        assertSame(AgentCallerService.ExtractedTitles.EMPTY, actual);
    }

    @Test
    @DisplayName("抽取抛出异常时应降级为 EMPTY")
    void shouldFallbackOnExtractionError() {
        AgentCallerService service = new AgentCallerService(
                null, null, aiChatService, lightModel);
        when(aiChatService.entity(any(ChatModel.class), anyString(), anyString(), eq(AgentCallerService.ExtractedTitles.class)))
                .thenThrow(new RuntimeException("llm down"));

        AgentCallerService.ExtractedTitles actual = service.extractTitles("回复");
        assertSame(AgentCallerService.ExtractedTitles.EMPTY, actual);
    }

    @Test
    @DisplayName("空回复直接返回 EMPTY")
    void shouldReturnEmptyForBlank() {
        AgentCallerService service = new AgentCallerService(null, null, aiChatService, lightModel);
        assertSame(AgentCallerService.ExtractedTitles.EMPTY, service.extractTitles(null));
        assertSame(AgentCallerService.ExtractedTitles.EMPTY, service.extractTitles("   "));
    }
}
