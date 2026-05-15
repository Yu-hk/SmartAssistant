/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.config;

import com.example.smartassistant.common.config.DeepSeekApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 自定义 DeepSeek ChatModel，禁用思考模式以支持工具调用。
 * <p>
 * HTTP 通信委托给 common 模块的 {@link DeepSeekApiClient}，
 * 避免在各模块间重复实现。
 */
@Component("deepSeekChatModel")
public class CustomDeepSeekChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CustomDeepSeekChatModel.class);
    private final DeepSeekApiClient apiClient;
    private final double temperature;
    private final int maxTokens;

    public CustomDeepSeekChatModel(
            @Value("${spring.ai.deepseek.api-key}") String apiKey,
            @Value("${spring.ai.deepseek.chat.options.temperature:0.5}") double temperature,
            @Value("${spring.ai.deepseek.chat.options.max-tokens:4096}") int maxTokens) {
        this.apiClient = new DeepSeekApiClient(apiKey);
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        log.info("[CustomDeepSeek] General initialized (temperature={}, maxTokens={})", temperature, maxTokens);
    }

    @Override public ChatResponse call(Prompt prompt) {
        try {
            String requestJson = apiClient.buildRequestJson("deepseek-v4-flash", temperature, maxTokens, buildMessagesJson(prompt));
            log.debug("[CustomDeepSeek] Request: {}", requestJson.substring(0, Math.min(200, requestJson.length())));

            String responseBody = apiClient.sendRequest(requestJson);
            return parseResponse(responseBody);
        } catch (Exception e) {
            log.error("[CustomDeepSeek] call failed: {}", e.getMessage(), e);
            throw new RuntimeException("DeepSeek call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    private ArrayNode buildMessagesJson(Prompt prompt) {
        ObjectMapper om = apiClient.getObjectMapper();
        ArrayNode messages = om.createArrayNode();
        for (var instruction : prompt.getInstructions()) {
            ObjectNode msg = messages.addObject();
            if (instruction.getMessageType() == MessageType.USER) {
                msg.put("role", "user").put("content", instruction.getText());
            } else if (instruction.getMessageType() == MessageType.SYSTEM) {
                msg.put("role", "system").put("content", instruction.getText());
            } else if (instruction.getMessageType() == MessageType.ASSISTANT) {
                AssistantMessage am = (AssistantMessage) instruction;
                msg.put("role", "assistant").put("content", am.getText() != null ? am.getText() : "");
                if (!am.getToolCalls().isEmpty()) {
                    var tools = msg.putArray("tool_calls");
                    for (var tc : am.getToolCalls()) {
                        var t = tools.addObject();
                        t.put("id", tc.id()).put("type", "function");
                        t.putObject("function").put("name", tc.name()).put("arguments", tc.arguments());
                    }
                }
            } else if (instruction.getMessageType() == MessageType.TOOL) {
                for (var r : ((ToolResponseMessage) instruction).getResponses()) {
                    messages.addObject().put("role", "tool").put("tool_call_id", r.id()).put("content", r.responseData());
                }
            }
        }
        return messages;
    }

    private ChatResponse parseResponse(String responseBody) throws Exception {
        JsonNode root = apiClient.parseResponse(responseBody);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return new ChatResponse(List.of());
        }
        List<Generation> generations = new ArrayList<>();
        for (JsonNode choice : choices) {
            JsonNode message = choice.get("message");
            if (message == null) continue;
            String content = message.has("content") && !message.get("content").isNull()
                    ? message.get("content").asText() : "";
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            JsonNode tcNode = message.get("tool_calls");
            if (tcNode != null && tcNode.isArray()) {
                for (JsonNode tc : tcNode) {
                    toolCalls.add(new AssistantMessage.ToolCall(
                            tc.get("id").asText(), tc.get("type").asText(),
                            tc.at("/function/name").asText(), tc.at("/function/arguments").asText()));
                }
            }
            var builder = AssistantMessage.builder().content(content);
            if (!toolCalls.isEmpty()) builder.toolCalls(toolCalls);
            generations.add(new Generation(builder.build()));
        }
        return new ChatResponse(generations);
    }

    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() { return null; }
}
