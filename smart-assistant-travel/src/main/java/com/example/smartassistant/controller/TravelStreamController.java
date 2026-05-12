package com.example.smartassistant.controller;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.service.agent.StreamingTravelAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Travel 服务实时思考控制器
 * <p>
 * 提供 SSE 流式输出，实时展示 AI 推理过程（Think → Tool → Observation）
 */
@RestController
@RequestMapping("/travel/stream")
@Slf4j
public class TravelStreamController {

    private final ReactAgent locationWeatherAgent;
    private final StreamingTravelAgentService streamingAgentService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public TravelStreamController(
            @Qualifier("locationWeatherAgent") ReactAgent locationWeatherAgent,
            StreamingTravelAgentService streamingAgentService) {
        this.locationWeatherAgent = locationWeatherAgent;
        this.streamingAgentService = streamingAgentService;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String message,
            @RequestParam(required = false, defaultValue = "true") boolean showThinking) {

        log.info("[TravelStream] 开始流式对话: message={}, showThinking={}", message, showThinking);
        SseEmitter emitter = new SseEmitter(360000L);

        executor.execute(() -> {
            try {
                streamingAgentService.streamWithThinking(message, showThinking)
                    .subscribe(
                        event -> {
                            try {
                                String eventType = switch (event.getType()) {
                                    case THINKING -> "thinking";
                                    case TOOL_CALL -> "tool_call";
                                    case TOOL_RESULT -> "tool_result";
                                    case RESPONSE -> "response";
                                    case ERROR -> "error";
                                    case DONE -> "done";
                                };
                                emitter.send(SseEmitter.event()
                                    .name(eventType)
                                    .data(event.toSseData(), MediaType.APPLICATION_JSON));
                            } catch (IOException e) {
                                log.error("[TravelStream] 发送 SSE 事件失败: {}", e.getMessage());
                            }
                        },
                        error -> {
                            String errMsg = error.getMessage();
                            // ⭐ 拦截模型不支持图片的错误
                            if (errMsg != null && (errMsg.contains("image") || errMsg.contains("png") || errMsg.contains("jpg") || errMsg.contains("does not support"))) {
                                errMsg = "当前模型不支持图片输入，请避免在问题中包含图片引用";
                                log.warn("[TravelStream] 拦截图片输入错误");
                            }
                            log.error("[TravelStream] 流式对话异常: {}", errMsg, error);
                            try {
                                emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data("{\"type\":\"error\",\"content\":\"" + escapeJson(errMsg) + "\"}"));
                                    emitter.complete();
                            } catch (IOException e) {
                                log.debug("[TravelStream] 发送 error 事件后关闭 emitter 异常: {}", e.getMessage());
                            }
                        },
                        () -> {
                            try {
                                emitter.complete();
                            } catch (Exception e) {
                                log.debug("[TravelStream] 完成 emitter 异常: {}", e.getMessage());
                            }
                        }
                    );
            } catch (Exception e) {
                log.error("[TravelStream] 流式对话异常: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"type\":\"error\",\"content\":\"" + escapeJson(e.getMessage()) + "\"}"));
                    emitter.complete();
                } catch (IOException e2) {
                    log.debug("[TravelStream] 发送错误事件后关闭 emitter 异常: {}", e2.getMessage());
                }
            }
        });

        return emitter;
    }

    @PostMapping("/chat/sync")
    public String chatSync(@RequestParam String message) {
        log.info("[TravelStream] 同步对话: {}", message);
        try {
            var response = locationWeatherAgent.call(message);
            if (response != null) {
                return response.getText();
            }
            return "Agent 返回为空";
        } catch (Exception e) {
            log.error("[TravelStream] 同步对话异常: {}", e.getMessage(), e);
            return "处理失败: " + e.getMessage();
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
