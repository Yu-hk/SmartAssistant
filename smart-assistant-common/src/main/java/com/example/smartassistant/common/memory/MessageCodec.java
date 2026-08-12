/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

/**
 * {@link Message} 的多态类型安全编解码器。
 *
 * <h2>背景与风险</h2>
 * <p>{@link Message} 是 {@code org.springframework.ai.chat.messages} 包下的接口，
 * 运行时实际为 {@link UserMessage}/{@link AssistantMessage}/{@link SystemMessage} 等具体子类。
 * 若直接把 {@code List<Message>} 交给 Jackson 序列化后再反序列化，由于接口类型擦除 +
 * Jackson 默认不做多态判别，反序列化会失败（{@code InvalidDefinitionException}）或
 * 退化为错误类型，导致记忆数据损坏。</p>
 *
 * <h2>方案</h2>
 * <p>本编解码器在序列化时显式写入 {@code type} 判别字段（{@link MessageType} 的名称），
 * 反序列化时按 {@code type} 路由回正确子类构造器，全程不依赖对 spring-ai 源码的注解修改。
 * {@code metadata} 为异构 {@code Map<String,Object>}，故关闭未知属性失败并注册
 * {@link JavaTimeModule} 以兼容其中的时间类型。</p>
 *
 * <p>该组件与存储介质无关，可被 {@link RedisChatMemory} 或任何需要持久化 {@code Message} 的
 * 场景复用。</p>
 */
public final class MessageCodec {

    /**
     * 判别字段名。
     */
    private static final String FIELD_TYPE = "type";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private MessageCodec() {
    }

    /**
     * 将单条消息编码为带类型判别的 JSON 字符串。
     *
     * @param message 非空消息
     * @return JSON 字符串（含 {@code type} 字段）
     */
    public static String encode(Message message) {
        if (message == null) {
            return null;
        }
        try {
            WireEnvelope envelope = WireEnvelope.from(message);
            return MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Message 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将一条 JSON 字符串解码回具体子类消息。
     *
     * @param json 由 {@link #encode(Message)} 产生的 JSON
     * @return 具体子类实例（UserMessage/AssistantMessage/SystemMessage），无法识别时回退为 UserMessage
     */
    public static Message decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            WireEnvelope envelope = MAPPER.readValue(json, WireEnvelope.class);
            if (envelope == null || envelope.type == null) {
                return null;
            }
            Map<String, Object> md = envelope.metadata;
            List<AssistantMessage.ToolCall> calls =
                    envelope.toolCalls != null ? envelope.toolCalls : List.of();
            MessageType type = parseType(envelope.type);
            return switch (type) {
                case ASSISTANT -> AssistantMessage.builder()
                        .content(envelope.text)
                        .properties(md)
                        .toolCalls(calls)
                        .build();
                case SYSTEM -> SystemMessage.builder()
                        .text(envelope.text)
                        .metadata(md)
                        .build();
                case USER, TOOL -> UserMessage.builder()
                        .text(envelope.text)
                        .metadata(md)
                        .build();
            };
        } catch (Exception e) {
            throw new IllegalStateException("Message 反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将消息列表编码为 JSON 数组字符串。
     */
    public static String encodeList(List<Message> messages) {
        if (messages == null) {
            return "[]";
        }
        try {
            WireEnvelope[] envelopes = messages.stream()
                    .map(WireEnvelope::from)
                    .toArray(WireEnvelope[]::new);
            return MAPPER.writeValueAsString(envelopes);
        } catch (Exception e) {
            throw new IllegalStateException("Message 列表序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 JSON 数组字符串解码回具体子类消息列表。
     */
    public static List<Message> decodeList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            WireEnvelope[] envelopes = MAPPER.readValue(json, WireEnvelope[].class);
            if (envelopes == null) {
                return List.of();
            }
            return java.util.Arrays.stream(envelopes)
                    .map(e -> {
                        MessageType type = parseType(e.type);
                        Map<String, Object> md = e.metadata;
                        List<AssistantMessage.ToolCall> calls =
                                e.toolCalls != null ? e.toolCalls : List.of();
                        return switch (type) {
                            case ASSISTANT -> (Message) AssistantMessage.builder()
                                    .content(e.text)
                                    .properties(md)
                                    .toolCalls(calls)
                                    .build();
                            case SYSTEM -> (Message) SystemMessage.builder()
                                    .text(e.text)
                                    .metadata(md)
                                    .build();
                            default -> (Message) UserMessage.builder()
                                    .text(e.text)
                                    .metadata(md)
                                    .build();
                        };
                    })
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Message 列表反序列化失败: " + e.getMessage(), e);
        }
    }

    private static MessageType parseType(String raw) {
        if (raw == null) {
            return MessageType.USER;
        }
        try {
            return MessageType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 兼容历史 / 未知类型，统一降级为 USER
            return MessageType.USER;
        }
    }

    /**
     * 线缆传输信封：携带类型判别 + 文本 + metadata + toolCalls。
     * 字段命名贴合 spring-ai 的结构，便于后续排查。
     */
    public static class WireEnvelope {
        public String type;
        public String text;
        public Map<String, Object> metadata;
        public List<AssistantMessage.ToolCall> toolCalls;

        public WireEnvelope() {
        }

        static WireEnvelope from(Message message) {
            WireEnvelope e = new WireEnvelope();
            e.type = message.getMessageType().getValue();
            e.text = message.getText();
            e.metadata = message.getMetadata();
            if (message instanceof AssistantMessage am) {
                e.toolCalls = am.getToolCalls();
            }
            return e;
        }
    }
}
