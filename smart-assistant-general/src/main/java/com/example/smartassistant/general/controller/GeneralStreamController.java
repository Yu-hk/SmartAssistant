package com.example.smartassistant.general.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/general/stream")
public class GeneralStreamController {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public GeneralStreamController(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            ObjectMapper objectMapper) {
        this.chatClient = ChatClient.create(chatModel);
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamChat(
            @RequestParam String message,
            @RequestParam(defaultValue = "true") boolean showThinking,
            HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");

        if (showThinking) {
            send(response, "thinking", Map.of(
                    "type", "thinking",
                    "content", "正在生成回答..."));
        }

        try {
            String result = chatClient.prompt().user(message).call().content();
            send(response, "text", Map.of(
                    "type", "text",
                    "content", result != null ? result : ""));
            send(response, "done", Map.of("type", "done"));
        } catch (Exception e) {
            send(response, "error", Map.of(
                    "type", "error",
                    "content", "回答生成失败：" + safeMessage(e)));
            send(response, "done", Map.of("type", "done"));
        }
    }

    @PostMapping(value = "/chat/sync", produces = MediaType.TEXT_PLAIN_VALUE)
    public String chatSync(@RequestBody Map<String, Object> request) {
        Object question = request.getOrDefault("question", request.get("message"));
        if (question == null || question.toString().isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        String result = chatClient.prompt().user(question.toString()).call().content();
        return result != null ? result : "";
    }

    private void send(HttpServletResponse response, String event, Map<String, ?> data)
            throws IOException {
        response.getWriter().write("event: " + event + "\n");
        response.getWriter().write("data: " + objectMapper.writeValueAsString(data) + "\n\n");
        response.getWriter().flush();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
