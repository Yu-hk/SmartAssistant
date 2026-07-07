/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link MessageCodec} 的 <b>Message 多态类型安全</b> 编解码。
 *
 * <p>这是 ① RedisChatMemory 类型安全序列化方案的核心交付：证明 {@code List<Message>}
 * （接口类型、运行时为多子类）经 {@link MessageCodec} 往返后不会丢失具体类型，
 * 从而规避 Jackson 直接序列化 {@code List<Message>} 的类型擦除损坏问题。</p>
 *
 * <p>{@link RedisChatMemory} 本身仅做“列表操作 + StringRedisTemplate 读写”，
 * 编解码完全委托本类，故其正确性由编译 + 本测试间接保证；集成行为由本地/CI Redis 环境验证。</p>
 */
class RedisChatMemoryTest {

    @Test
    void messageCodec_roundtrip_preservesConcreteType_user() {
        UserMessage user = new UserMessage("你好");
        String json = MessageCodec.encode(user);
        assertTrue(json.contains("\"type\":\"user\""), "应包含 user 类型判别字段");
        Message back = MessageCodec.decode(json);
        assertEquals(MessageType.USER, back.getMessageType());
        assertEquals("你好", back.getText());
        assertInstanceOf(UserMessage.class, back);
    }

    @Test
    void messageCodec_roundtrip_preservesConcreteType_system() {
        SystemMessage sys = SystemMessage.builder().text("你是助手").build();
        Message back = MessageCodec.decode(MessageCodec.encode(sys));
        assertEquals(MessageType.SYSTEM, back.getMessageType());
        assertInstanceOf(SystemMessage.class, back);
    }

    @Test
    void messageCodec_roundtrip_preservesAssistantToolCalls() {
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall("call_1", "function", "getWeather", "{\"city\":\"北京\"}");
        AssistantMessage assist = AssistantMessage.builder()
                .content("我来查天气")
                .properties(Map.of("source", "tool"))
                .toolCalls(List.of(toolCall))
                .build();
        String json = MessageCodec.encode(assist);
        Message back = MessageCodec.decode(json);
        assertEquals(MessageType.ASSISTANT, back.getMessageType());
        assertInstanceOf(AssistantMessage.class, back);
        AssistantMessage typed = (AssistantMessage) back;
        assertEquals(1, typed.getToolCalls().size());
        assertEquals("getWeather", typed.getToolCalls().get(0).name());
        assertEquals("{\"city\":\"北京\"}", typed.getToolCalls().get(0).arguments());
    }

    @Test
    void messageCodec_listRoundtrip_preservesOrderAndType() {
        List<Message> origin = List.of(
                SystemMessage.builder().text("system").build(),
                new UserMessage("user"),
                AssistantMessage.builder().content("assistant").properties(Map.of()).build()
        );
        String json = MessageCodec.encodeList(origin);
        List<Message> back = MessageCodec.decodeList(json);
        assertEquals(3, back.size());
        assertEquals(MessageType.SYSTEM, back.get(0).getMessageType());
        assertEquals(MessageType.USER, back.get(1).getMessageType());
        assertEquals(MessageType.ASSISTANT, back.get(2).getMessageType());
    }

    @Test
    void messageCodec_decodeNullSafe() {
        assertNull(MessageCodec.decode(null));
        assertNull(MessageCodec.decode(""));
        assertEquals(0, MessageCodec.decodeList(null).size());
        assertEquals(0, MessageCodec.decodeList("").size());
        assertEquals("[]", MessageCodec.encodeList(List.of()));
    }

    @Test
    void messageCodec_metadataPreserved() {
        UserMessage withMeta = UserMessage.builder()
                .text("带元数据")
                .metadata(Map.of("traceId", "abc123", "ts", 1700000000L))
                .build();
        Message back = MessageCodec.decode(MessageCodec.encode(withMeta));
        assertEquals("abc123", back.getMetadata().get("traceId"));
        assertEquals(1700000000L, back.getMetadata().get("ts"));
    }
}
