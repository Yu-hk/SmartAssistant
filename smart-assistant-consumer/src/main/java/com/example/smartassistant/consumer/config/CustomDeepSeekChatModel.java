package com.example.smartassistant.consumer.config;

import com.example.smartassistant.common.config.DeepSeekApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

@Component("deepSeekChatModel")
public class CustomDeepSeekChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CustomDeepSeekChatModel.class);
    private final DeepSeekApiClient apiClient;

    public CustomDeepSeekChatModel(@Value("${spring.ai.deepseek.api-key}") String apiKey) {
        this.apiClient = new DeepSeekApiClient(apiKey);
        log.info("[CustomDeepSeek] Consumer initialized (thinking_mode=disabled)");
    }

    @Override public ChatResponse call(Prompt prompt) {
        try {
            String requestJson = apiClient.buildRequestJson("deepseek-v4-flash", 0.7, 4096, buildMessages(prompt));
            return parseResponse(apiClient.sendRequest(requestJson));
        } catch (Exception e) { throw new RuntimeException("DeepSeek call failed: " + e.getMessage(), e); }
    }

    @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }

    private ArrayNode buildMessages(Prompt prompt) {
        var om = apiClient.getObjectMapper();
        var msgs = om.createArrayNode();
        for (var inst : prompt.getInstructions()) {
            var msg = msgs.addObject();
            if (inst.getMessageType() == MessageType.USER) msg.put("role", "user").put("content", inst.getText());
            else if (inst.getMessageType() == MessageType.SYSTEM) msg.put("role", "system").put("content", inst.getText());
            else if (inst.getMessageType() == MessageType.ASSISTANT) {
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
                for (var r : ((ToolResponseMessage) inst).getResponses())
                    msgs.addObject().put("role", "tool").put("tool_call_id", r.id()).put("content", r.responseData());
            }
        }
        return msgs;
    }

    private ChatResponse parseResponse(String body) throws Exception {
        JsonNode root = apiClient.parseResponse(body);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) return new ChatResponse(List.of());
        List<Generation> gens = new ArrayList<>();
        for (JsonNode c : choices) {
            JsonNode m = c.get("message");
            if (m == null) continue;
            String content = m.has("content") && !m.get("content").isNull() ? m.get("content").asText() : "";
            List<AssistantMessage.ToolCall> tcs = new ArrayList<>();
            JsonNode tcN = m.get("tool_calls");
            if (tcN != null && tcN.isArray()) for (JsonNode tc : tcN)
                tcs.add(new AssistantMessage.ToolCall(tc.get("id").asText(), tc.get("type").asText(),
                        tc.at("/function/name").asText(), tc.at("/function/arguments").asText()));
            var b = AssistantMessage.builder().content(content);
            if (!tcs.isEmpty()) b.toolCalls(tcs);
            gens.add(new Generation(b.build()));
        }
        return new ChatResponse(gens);
    }

    @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() { return null; }
}
