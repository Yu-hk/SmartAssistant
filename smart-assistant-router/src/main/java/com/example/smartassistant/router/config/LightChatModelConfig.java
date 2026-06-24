/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.config;

import com.example.smartassistant.common.config.OllamaApiClient;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量模型配置——使用 qwen2.5:3b 处理简单推理任务。
 * <p>
 * 7B 模型（@Primary）处理：SmartReActAgent、HybridDataQueryService 等复杂推理。
 * 3B 模型（@Qualifier("lightChatModel")）处理：分类、改写、摘要、评估等简单任务。
 * </p>
 */
@Configuration
public class LightChatModelConfig {

    private static final Logger log = LoggerFactory.getLogger(LightChatModelConfig.class);

    @Bean
    @Qualifier("lightChatModel")
    public ChatModel lightChatModel(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${router.light-model.name:qwen2.5:3b}") String model,
            @Value("${router.light-model.temperature:0.1}") double temperature) {
        log.info("[LightChatModel] initialized (model={}, baseUrl={})", model, baseUrl);
        return new LightOllamaChatModel(baseUrl, model, temperature);
    }

    /**
     * 轻量版 ChatModel 实现，结构与 CustomOllamaChatModel 一致但使用不同模型。
     */
    public static class LightOllamaChatModel implements ChatModel {

        private final OllamaApiClient apiClient;
        private final String model;
        private final double temperature;

        public LightOllamaChatModel(String baseUrl, String model, double temperature) {
            this.apiClient = new OllamaApiClient(baseUrl);
            this.model = model;
            this.temperature = temperature;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            try {
                String requestJson = apiClient.buildRequestJson(
                        model, temperature, buildMessages(prompt));
                String responseBody = apiClient.sendRequest(requestJson);
                return parseResponse(responseBody);
            } catch (Exception e) {
                throw new RuntimeException("LightModel call failed: " + e.getMessage(), e);
            }
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getDefaultOptions() { return null; }

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
}
