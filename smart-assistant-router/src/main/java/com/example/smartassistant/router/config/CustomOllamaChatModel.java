/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.config;

import com.example.smartassistant.common.config.OllamaApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义 Ollama ChatModel — @Primary 默认推理通道
 * <p>
 * 实现 Spring AI 的 {@link ChatModel} 接口，
 * 通过 {@link OllamaApiClient} 调用本地 Ollama API。
 * 作为 SmartAssistant 的主要推理引擎，所有 ChatClient.Builder
 * 自动注入此实现。
 */
@Component("ollamaChatModel")
@Primary
public class CustomOllamaChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CustomOllamaChatModel.class);

    private final OllamaApiClient apiClient;
    private final String model;
    private final double temperature;

    public CustomOllamaChatModel(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${spring.ai.ollama.chat.options.model:qwen2.5:7b}") String model,
            @Value("${spring.ai.ollama.chat.options.temperature:0.7}") double temperature) {
        this.apiClient = new OllamaApiClient(baseUrl);
        this.model = model;
        this.temperature = temperature;
        log.info("[CustomOllama] Router initialized (model={}, baseUrl={}, temperature={})", model, baseUrl, temperature);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            String requestJson = apiClient.buildRequestJson(
                    model, temperature, buildMessages(prompt));
            String responseBody = apiClient.sendRequest(requestJson);
            return parseResponse(responseBody);
        } catch (Exception e) {
            log.error("[CustomOllama] call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Ollama call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return null;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode buildMessages(Prompt prompt) {
        var msgs = apiClient.getObjectMapper().createArrayNode();
        for (var inst : prompt.getInstructions()) {
            var msg = msgs.addObject();
            if (inst.getMessageType() == MessageType.USER) {
                msg.put("role", "user").put("content", inst.getText());
            } else if (inst.getMessageType() == MessageType.SYSTEM) {
                msg.put("role", "system").put("content", inst.getText());
            } else if (inst.getMessageType() == MessageType.ASSISTANT) {
                AssistantMessage am = (AssistantMessage) inst;
                msg.put("role", "assistant").put("content", am.getText() != null ? am.getText() : "");
                if (!am.getToolCalls().isEmpty()) {
                    var tools = msg.putArray("tool_calls");
                    for (var tc : am.getToolCalls()) {
                        var t = tools.addObject();
                        t.put("id", tc.id()).put("type", "function");
                        t.putObject("function").put("name", tc.name()).put("arguments", tc.arguments());
                    }
                }
            } else if (inst.getMessageType() == MessageType.TOOL) {
                for (var r : ((ToolResponseMessage) inst).getResponses()) {
                    msgs.addObject().put("role", "tool")
                            .put("tool_call_id", r.id())
                            .put("content", r.responseData());
                }
            }
        }
        return msgs;
    }

    private ChatResponse parseResponse(String body) throws Exception {
        String text = apiClient.parseResponseText(body);
        List<Generation> gens = new ArrayList<>();
        gens.add(new Generation(AssistantMessage.builder().content(text).build()));
        return new ChatResponse(gens);
    }
}
