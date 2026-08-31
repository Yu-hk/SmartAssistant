package com.example.smartassistant.router.service.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * DeepSeek 非思考模式规划客户端。
 *
 * <p>V4 默认启用 thinking，短 DAG 编译会把输出预算耗尽在 reasoning_content。
 * Spring AI 2.0 的 DeepSeek 请求模型尚未暴露 thinking 开关，因此此处仅对任务规划
 * 使用官方 Chat Completions 参数 {@code thinking.type=disabled}。</p>
 */
@Component
public class DeepSeekPlanningClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public DeepSeekPlanningClient(
            ObjectMapper objectMapper,
            @Value("${spring.ai.deepseek.api-key:${DEEPSEEK_API_KEY:}}") String apiKey,
            @Value("${spring.ai.deepseek.base-url:${DEEPSEEK_BASE_URL:https://api.deepseek.com}}") String baseUrl,
            @Value("${router.light-model.name:${MODEL_LIGHT:${DEEPSEEK_LIGHT_MODEL:}}}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DeepSeek API key is required for task planning");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("A task-planning model name is required");
        }
        this.objectMapper = objectMapper;
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public String complete(String prompt, int maxTokens) {
        Map<String, Object> request = requestBody(model, prompt, maxTokens);
        return complete(request);
    }

    public String modelName() {
        return model;
    }

    /**
     * Executes a structured intent-analysis call with the selected DeepSeek tier while
     * explicitly disabling thinking. This keeps Flash/Pro model selection without
     * allowing reasoning_content to consume the whole JSON output budget.
     */
    public String complete(String selectedModel, String systemPrompt,
                           String userMessage, int maxTokens) {
        Map<String, Object> request = requestBody(
                selectedModel, systemPrompt, userMessage, maxTokens);
        return complete(request);
    }

    private String complete(Map<String, Object> request) {
        String raw = restClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(String.class);
        return extractContent(raw, objectMapper);
    }

    static Map<String, Object> requestBody(String model, String prompt, int maxTokens) {
        return requestBody(model, null, prompt, maxTokens);
    }

    static Map<String, Object> requestBody(String model, String systemPrompt,
                                           String userMessage, int maxTokens) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content",
                userMessage != null ? userMessage : ""));
        return Map.of(
                "model", model,
                "messages", List.copyOf(messages),
                "thinking", Map.of("type", "disabled"),
                "max_tokens", Math.max(256, maxTokens),
                "temperature", 0.1);
    }

    static String extractContent(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) return "";
        try {
            JsonNode content = objectMapper.readTree(raw)
                    .path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : "";
        } catch (Exception e) {
            throw new IllegalStateException("Invalid DeepSeek planning response", e);
        }
    }
}
