/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * DeepSeek API 底层 HTTP 客户端
 * <p>
 * 封装直接调用 DeepSeek API 的通用逻辑（禁用 thinking_mode），
 * 各模块的 {@code CustomDeepSeekChatModel} 可委托此客户端完成 HTTP 通信，
 * 避免重复实现。
 * <p>
 * 不依赖 Spring AI，可安全地在 common 模块中共享。
 */
public class DeepSeekApiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekApiClient.class);

    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekApiClient(String apiKey) {
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 构建请求 JSON：禁用 thinking_mode，保持与 Spring AI Prompt 兼容的消息结构
     *
     * @param model        模型名称
     * @param temperature  温度参数
     * @param maxTokens    最大 Token 数
     * @param messagesNode 消息列表（必填）
     * @param toolsNode    工具定义列表（可为 null，为 null 时不发送 tools 字段）
     */
    public String buildRequestJson(String model, double temperature, int maxTokens,
                                   JsonNode messagesNode, JsonNode toolsNode) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);
        root.put("stream", false);

        ObjectNode thinking = objectMapper.createObjectNode();
        thinking.put("type", "disabled");
        root.set("extra_body", objectMapper.createObjectNode().set("thinking", thinking));

        root.set("messages", messagesNode);
        if (toolsNode != null) {
            root.set("tools", toolsNode);
        }
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 发送请求并获取响应
     */
    public String sendRequest(String requestJson) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[DeepSeekApiClient] API error: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("DeepSeek API error: HTTP " + response.statusCode() + " - " + response.body());
            }
            return response.body();
        } catch (Exception e) {
            log.error("[DeepSeekApiClient] request failed: {}", e.getMessage(), e);
            throw new RuntimeException("DeepSeek call failed: " + e.getMessage(), e);
        }
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
}
