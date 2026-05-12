package com.example.smartassistant.travel.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Component("deepSeekChatModel")
public class CustomDeepSeekChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CustomDeepSeekChatModel.class);

    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CustomDeepSeekChatModel(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.deepseek.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("[CustomDeepSeek] 初始化自定义 DeepSeek ChatModel (thinking_mode=disabled)");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            String requestJson = buildRequestJson(prompt);
            log.debug("[CustomDeepSeek] Request: {}", requestJson.substring(0, Math.min(200, requestJson.length())));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[CustomDeepSeek] API error: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("DeepSeek API error: HTTP " + response.statusCode() + " - " + response.body());
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            log.error("[CustomDeepSeek] Call failed: {}", e.getMessage(), e);
            throw new RuntimeException("DeepSeek call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    private String buildRequestJson(Prompt prompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", "deepseek-v4-flash");
        root.put("temperature", 0.7);
        root.put("max_tokens", 4096);
        root.put("stream", false);

        ObjectNode thinking = objectMapper.createObjectNode();
        thinking.put("type", "disabled");
        root.set("extra_body", objectMapper.createObjectNode().set("thinking", thinking));

        var messages = root.putArray("messages");
        for (var instruction : prompt.getInstructions()) {
            ObjectNode msg = messages.addObject();

            if (instruction.getMessageType() == MessageType.USER) {
                msg.put("role", "user");
                msg.put("content", instruction.getText());
            } else if (instruction.getMessageType() == MessageType.SYSTEM) {
                msg.put("role", "system");
                msg.put("content", instruction.getText());
            } else if (instruction.getMessageType() == MessageType.ASSISTANT) {
                AssistantMessage assistantMsg = (AssistantMessage) instruction;
                msg.put("role", "assistant");
                msg.put("content", assistantMsg.getText() != null ? assistantMsg.getText() : "");

                if (!assistantMsg.getToolCalls().isEmpty()) {
                    var toolCalls = msg.putArray("tool_calls");
                    for (var tc : assistantMsg.getToolCalls()) {
                        ObjectNode tcNode = toolCalls.addObject();
                        tcNode.put("id", tc.id());
                        tcNode.put("type", "function");
                        ObjectNode func = tcNode.putObject("function");
                        func.put("name", tc.name());
                        func.put("arguments", tc.arguments());
                    }
                }
            } else if (instruction.getMessageType() == MessageType.TOOL) {
                ToolResponseMessage toolMsg = (ToolResponseMessage) instruction;
                for (var response : toolMsg.getResponses()) {
                    ObjectNode toolResult = messages.addObject();
                    toolResult.put("role", "tool");
                    toolResult.put("tool_call_id", response.id());
                    toolResult.put("content", response.responseData());
                }
            }
        }

        return objectMapper.writeValueAsString(root);
    }

    private ChatResponse parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
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
            JsonNode toolCallsNode = message.get("tool_calls");
            if (toolCallsNode != null && toolCallsNode.isArray()) {
                for (JsonNode tc : toolCallsNode) {
                    String id = tc.get("id").asText();
                    String type = tc.get("type").asText();
                    String name = tc.at("/function/name").asText();
                    String arguments = tc.at("/function/arguments").asText();
                    toolCalls.add(new AssistantMessage.ToolCall(id, type, name, arguments));
                }
            }

            var msgBuilder = AssistantMessage.builder()
                    .content(content);
            if (!toolCalls.isEmpty()) {
                msgBuilder.toolCalls(toolCalls);
            }
            Generation generation = new Generation(msgBuilder.build());
            generations.add(generation);
        }

        return new ChatResponse(generations);
    }

    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
        return null;
    }
}
