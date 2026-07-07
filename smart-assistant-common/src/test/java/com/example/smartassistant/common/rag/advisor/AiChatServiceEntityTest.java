/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 验证 {@link AiChatService#entity(ChatModel, String, Class)} 的结构化输出封装。
 *
 * <p>为避免依赖运行态 Jackson 版本差异（测试环境 jackson-annotations 与目标不一致），
 * 采用 {@code MockedStatic(ChatClient)} 拦截 {@link ChatClient#builder(ChatModel)}，
 * 直接桩化流式调用链，聚焦于验证「entity() 封装正确委派到 ChatClient.entity()」这一装配逻辑。</p>
 */
class AiChatServiceEntityTest {

    /** 测试用结构化载体 */
    record CityInfo(String name, int population) {
    }

    @Test
    void entity_shouldDelegateToChatClientEntity() {
        ChatModel model = mock(ChatModel.class);
        CityInfo expected = new CityInfo("北京", 21_890_000);

        try (var chatClientStatic = mockStatic(ChatClient.class)) {
            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            ChatClient client = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

            chatClientStatic.when(() -> ChatClient.builder(any(ChatModel.class))).thenReturn(builder);
            when(builder.build()).thenReturn(client);
            when(client.prompt()).thenReturn(promptSpec);
            when(promptSpec.user(anyString())).thenReturn(promptSpec);
            when(promptSpec.call()).thenReturn(callSpec);
            when(callSpec.entity(CityInfo.class)).thenReturn(expected);

            AiChatService service = new AiChatService(null, null, null, null);
            CityInfo actual = service.entity(model, "返回城市信息", CityInfo.class);

            assertThat(actual).isEqualTo(expected);
            assertThat(actual.name()).isEqualTo("北京");
            assertThat(actual.population()).isEqualTo(21_890_000);
        }
    }

    @Test
    void entity_withSystem_shouldDelegateToChatClientEntity() {
        ChatModel model = mock(ChatModel.class);
        CityInfo expected = new CityInfo("上海", 24_870_000);

        try (var chatClientStatic = mockStatic(ChatClient.class)) {
            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            ChatClient client = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

            chatClientStatic.when(() -> ChatClient.builder(any(ChatModel.class))).thenReturn(builder);
            when(builder.build()).thenReturn(client);
            when(client.prompt()).thenReturn(promptSpec);
            when(promptSpec.system(anyString())).thenReturn(promptSpec);
            when(promptSpec.user(anyString())).thenReturn(promptSpec);
            when(promptSpec.call()).thenReturn(callSpec);
            when(callSpec.entity(CityInfo.class)).thenReturn(expected);

            AiChatService service = new AiChatService(null, null, null, null);
            CityInfo actual = service.entity(model, "sys", "user", CityInfo.class);

            assertThat(actual).isEqualTo(expected);
        }
    }

    @Test
    void buildChatClient_shouldReturnConfiguredClient() {
        ChatModel model = mock(ChatModel.class);
        try (var chatClientStatic = mockStatic(ChatClient.class)) {
            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            ChatClient client = mock(ChatClient.class);
            chatClientStatic.when(() -> ChatClient.builder(any(ChatModel.class))).thenReturn(builder);
            when(builder.build()).thenReturn(client);

            AiChatService service = new AiChatService(
                    new SafeGuardAdvisor(), new TokenUsageAdvisor(null), null, null);
            assertThat(service.buildChatClient(model)).isSameAs(client);
        }
    }

    /** 断言 record 可被 Jackson 正常序列化（结构化输出契约自检，不依赖运行态 ChatModel） */
    @Test
    void cityInfo_record_shouldBeJacksonSerializable() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(new CityInfo("北京", 21_890_000));
        assertThat(json).contains("北京");
        CityInfo back = mapper.readValue(json, CityInfo.class);
        assertThat(back).isEqualTo(new CityInfo("北京", 21_890_000));
    }
}
