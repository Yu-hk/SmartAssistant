/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ConversationSummarizationService 单元测试
 * <p>
 * 测试叙事摘要的正常流程和 fail-safe 降级逻辑。
 */
@ExtendWith(MockitoExtension.class)
class ConversationSummarizationServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private ConversationSummarizationService service;

    @BeforeEach
    void setUp() {
        lenient().when(chatClientBuilder.build()).thenReturn(chatClient);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpec);

        service = new ConversationSummarizationService(chatClientBuilder);
    }

    @Test
    void normalDialog_shouldReturnNarrative() {
        String rawContent = """
                用户: 推荐一些北京美食
                助手: 北京烤鸭是全聚德最出名，另外涮羊肉推荐东来顺。
                用户: 有没有便宜点的？
                助手: 护国寺小吃有很多平价北京小吃，豆汁、焦圈都不错。
                """;
        String expectedNarrative = "用户查询北京美食推荐，系统推荐了全聚德烤鸭、东来顺涮羊肉，以及护国寺小吃的平价北京小吃。";

        when(callResponseSpec.content()).thenReturn(expectedNarrative);

        String result = service.summarize(rawContent);

        assertEquals(expectedNarrative, result);
        verify(chatClient).prompt();
        verify(requestSpec).user(anyString());
        verify(requestSpec).call();
        verify(callResponseSpec).content();
    }

    @Test
    void emptyContent_shouldReturnEmpty() {
        assertEquals("", service.summarize(""));
        assertEquals("", service.summarize(null));
        assertEquals("", service.summarize("   "));
    }

    @Test
    void llmReturnsNull_shouldFallbackToRaw() {
        String rawContent = "用户: 你好\n助手: 你好，有什么可以帮你的？";

        when(callResponseSpec.content()).thenReturn(null);

        String result = service.summarize(rawContent);
        assertEquals(rawContent, result);
    }

    @Test
    void llmReturnsBlank_shouldFallbackToRaw() {
        String rawContent = "用户: 天气如何\n助手: 今天晴天。";

        when(callResponseSpec.content()).thenReturn("   ");

        String result = service.summarize(rawContent);
        assertEquals(rawContent, result);
    }

    @Test
    void llmThrowsException_shouldFallbackToRaw() {
        String rawContent = "用户: 测试\n助手: 测试回复";

        when(chatClient.prompt()).thenThrow(new RuntimeException("LLM 超时"));

        String result = service.summarize(rawContent);
        assertEquals(rawContent, result);
    }

    @Test
    void llmReturnsTrimmedNarrative() {
        String rawContent = "用户: 推荐景点\n助手: 故宫、长城";
        String llmOutput = "  用户查询景点，系统推荐了故宫和长城。  ";

        when(callResponseSpec.content()).thenReturn(llmOutput);

        String result = service.summarize(rawContent);
        assertEquals("用户查询景点，系统推荐了故宫和长城。", result);
    }
}
