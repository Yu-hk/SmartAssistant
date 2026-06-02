/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Ollama API 底层 HTTP 客户端
 * <p>
 * 封装调用本地 Ollama API 的通用逻辑，
 * 各模块的 {@code CustomOllamaChatModel} 可委托此客户端完成 HTTP 通信，
 * 避免重复实现。
 * <p>
 * 不依赖 Spring AI，可安全地在 common 模块中共享。
 */
public class OllamaApiClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaApiClient.class);

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OllamaApiClient() {
        this(DEFAULT_BASE_URL);
    }

    public OllamaApiClient(String baseUrl) {
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 构建请求 JSON
     */
    public String buildRequestJson(String model, double temperature, JsonNode messagesNode) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        root.set("messages", messagesNode);

        ObjectNode options = objectMapper.createObjectNode();
        options.put("temperature", temperature);
        root.set("options", options);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 发送请求并获取响应
     */
    public String sendRequest(String requestJson) {
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "api/chat" : baseUrl + "/api/chat";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[OllamaApiClient] API error: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("Ollama API error: HTTP " + response.statusCode() + " - " + response.body());
            }
            return response.body();
        } catch (Exception e) {
            log.error("[OllamaApiClient] request failed: {}", e.getMessage(), e);
            throw new RuntimeException("Ollama call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 发送请求并获取响应（带自定义超时）
     */
    public String sendRequest(String requestJson, Duration timeout) {
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "api/chat" : baseUrl + "/api/chat";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[OllamaApiClient] API error: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("Ollama API error: HTTP " + response.statusCode() + " - " + response.body());
            }
            return response.body();
        } catch (Exception e) {
            log.error("[OllamaApiClient] request failed: {}", e.getMessage(), e);
            throw new RuntimeException("Ollama call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 构建消息数组（从 Prompt 指令转换）
     */
    public ArrayNode buildMessages(java.util.List<Object> instructions) {
        ArrayNode msgs = objectMapper.createArrayNode();
        for (Object inst : instructions) {
            if (inst instanceof org.springframework.ai.chat.messages.Message msg) {
                ObjectNode m = msgs.addObject();
                switch (msg.getMessageType()) {
                    case USER -> m.put("role", "user").put("content", msg.getText());
                    case SYSTEM -> m.put("role", "system").put("content", msg.getText());
                    case ASSISTANT -> {
                        m.put("role", "assistant");
                        m.put("content", msg.getText() != null ? msg.getText() : "");
                        if (msg instanceof org.springframework.ai.chat.messages.AssistantMessage am
                                && !am.getToolCalls().isEmpty()) {
                            m.put("content", am.getText() != null ? am.getText() : "");
                        }
                    }
                    case TOOL -> {
                        if (msg instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm) {
                            for (var r : trm.getResponses()) {
                                msgs.addObject().put("role", "tool")
                                        .put("tool_call_id", r.id())
                                        .put("content", r.responseData());
                            }
                        }
                    }
                    default -> m.put("role", "user").put("content", msg.getText());
                }
            }
        }
        return msgs;
    }

    /**
     * 构建简易消息数组（仅 system + user，不含 tool calls）
     */
    public ArrayNode buildSimpleMessages(String systemPrompt, String userMessage) {
        ArrayNode msgs = objectMapper.createArrayNode();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.addObject().put("role", "system").put("content", systemPrompt);
        }
        msgs.addObject().put("role", "user").put("content", userMessage);
        return msgs;
    }

    /**
     * 解析 API 响应，提取回复文本
     */
    public String parseResponseText(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode message = root.get("message");
        if (message == null) {
            return "";
        }
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) {
            return "";
        }
        return content.asText();
    }

    /**
     * 解析 API 响应为 JSON 节点
     */
    public JsonNode parseResponse(String responseBody) throws Exception {
        return objectMapper.readTree(responseBody);
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
