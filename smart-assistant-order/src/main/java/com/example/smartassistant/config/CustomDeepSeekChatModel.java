/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.config.DeepSeekApiClient;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义 DeepSeek ChatModel。
 * <p>
 * 绕过 Spring AI DeepSeek 自动配置，直接调用 DeepSeek API。
 * 支持工具定义（function calling），供 SmartReActAgent 使用。
 * </p>
 */
@Component("deepSeekChatModel")
public class CustomDeepSeekChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CustomDeepSeekChatModel.class);
    private final DeepSeekApiClient apiClient;
    private final double temperature;
    private final int maxTokens;

    /** ⭐ 注入的工具回调列表，由 SmartReActAgent 设置 */
    private List<ToolCallback> toolCallbacks;

    public CustomDeepSeekChatModel(
            @Value("${spring.ai.deepseek.api-key}") String apiKey,
            @Value("${spring.ai.deepseek.chat.options.temperature:0.5}") double temperature,
            @Value("${spring.ai.deepseek.chat.options.max-tokens:4096}") int maxTokens) {
        this.apiClient = new DeepSeekApiClient(apiKey);
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        log.info("[CustomDeepSeek] initialized (thinking_mode=disabled, temperature={}, maxTokens={})", temperature, maxTokens);
    }

    /** ⭐ 由外部设置工具回调列表（SmartReActAgent 在 execute 时调用） */
    public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = toolCallbacks;
    }

    @Override public ChatResponse call(Prompt prompt) {
        try {
            ArrayNode messagesNode = buildMessages(prompt);
            ArrayNode toolsNode = buildToolsNode();

            String requestJson = apiClient.buildRequestJson("deepseek-v4-flash", temperature, maxTokens, messagesNode, toolsNode);
            String apiResponse = apiClient.sendRequest(requestJson);
            ChatResponse chatResponse = parseResponse(apiResponse);
            return chatResponse;
        } catch (Exception e) {
            log.error("[CustomDeepSeek] LLM 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("DeepSeek call failed: " + e.getMessage(), e);
        }
    }

    @Override public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    /** 构建消息列表 */
    private ArrayNode buildMessages(Prompt prompt) {
        var om = apiClient.getObjectMapper();
        var msgs = om.createArrayNode();
        for (var inst : prompt.getInstructions()) {
            MessageType type = inst.getMessageType();
            
            // ⭐ TOOL 类型：不创建外层空 msg，直接创建 tool 消息
            if (type == MessageType.TOOL) {
                for (var r : ((ToolResponseMessage) inst).getResponses()) {
                    var toolMsg = msgs.addObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", r.id() != null ? r.id() : "");
                    toolMsg.put("content", r.responseData() != null ? r.responseData() : "");
                }
                continue; // ⭐ 跳过最后的 msgs.addObject()
            }
            
            var msg = msgs.addObject();
            if (type == MessageType.USER) {
                msg.put("role", "user").put("content", inst.getText());
            } else if (type == MessageType.SYSTEM) {
                msg.put("role", "system").put("content", inst.getText());
            } else if (type == MessageType.ASSISTANT) {
                AssistantMessage am = (AssistantMessage) inst;
                boolean hasToolCalls = am.getToolCalls() != null && !am.getToolCalls().isEmpty();
                if (hasToolCalls) {
                    // tool_call 消息 content 必须为 null（OpenAI 规范）
                    msg.put("role", "assistant");
                    msg.putNull("content");
                    var tools = msg.putArray("tool_calls");
                    for (var tc : am.getToolCalls()) {
                        var t = tools.addObject();
                        t.put("id", tc.id()).put("type", "function");
                        t.putObject("function").put("name", tc.name()).put("arguments", tc.arguments());
                    }
                } else {
                    msg.put("role", "assistant").put("content", am.getText() != null ? am.getText() : "");
                }
            } else {
                // 其他类型（如 UNKNOWN 等），兜底处理
                log.warn("[CustomDeepSeek] 未知消息类型: {}, 内容: {}", type, inst.getText());
                msg.put("role", "unknown").put("content", inst.getText() != null ? inst.getText() : "");
            }
        }
        return msgs;
    }

    /** ⭐ 构建工具定义数组（用于 function calling） */
    private ArrayNode buildToolsNode() {
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            log.debug("[CustomDeepSeek] 无已注册工具");
            return null;
        }

        var om = apiClient.getObjectMapper();
        var tools = om.createArrayNode();

        for (ToolCallback callback : toolCallbacks) {
            var def = callback.getToolDefinition();
            if (def == null) continue;

            var tool = tools.addObject();
            tool.put("type", "function");
            var fn = tool.putObject("function");
            fn.put("name", def.name());
            fn.put("description", def.description() != null ? def.description() : "");

            try {
                String schemaStr = def.inputSchema();
                if (schemaStr != null && !schemaStr.isBlank()) {
                    fn.set("parameters", om.readTree(schemaStr));
                } else {
                    ObjectNode params = om.createObjectNode();
                    params.put("type", "object");
                    params.set("properties", om.createObjectNode());
                    params.set("required", om.createArrayNode());
                    fn.set("parameters", params);
                }
            } catch (Exception e) {
                log.warn("[CustomDeepSeek] 解析工具 {} schema 失败: {}", def.name(), e.getMessage());
            }
        }

        log.info("[CustomDeepSeek] 构建 {} 个工具定义: {}", tools.size(), 
                tools.size() > 0 ? tools.get(0).at("/function/name").asText() + ", ..." : "无");
        return tools;
    }

    /** 解析 API 响应 */
    private ChatResponse parseResponse(String body) throws Exception {
        JsonNode root = apiClient.parseResponse(body);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return new ChatResponse(List.of());
        }

        List<Generation> gens = new ArrayList<>();
        for (JsonNode c : choices) {
            JsonNode m = c.get("message");
            if (m == null) continue;

            String content = m.has("content") && !m.get("content").isNull() ? m.get("content").asText() : "";
            List<AssistantMessage.ToolCall> tcs = new ArrayList<>();
            JsonNode tcN = m.get("tool_calls");
            if (tcN != null && tcN.isArray()) {
                for (JsonNode tc : tcN) {
                    tcs.add(new AssistantMessage.ToolCall(
                        tc.get("id").asText(),
                        tc.get("type").asText(),
                        tc.at("/function/name").asText(),
                        tc.at("/function/arguments").asText()));
                }
            }

            var b = AssistantMessage.builder().content(content);
            if (!tcs.isEmpty()) {
                b.toolCalls(tcs);
            }
            gens.add(new Generation(b.build()));
        }
        return new ChatResponse(gens);
    }

    @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
        return null;
    }
}
