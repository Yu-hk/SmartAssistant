/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * General Agent 流式响应控制器
 * <p>
 * 提供 SSE 流式输出，供 Consumer 的 {@code AgentStreamClient} 实时拉取 Agent 应答
 * （与 ProductStreamController 对齐：event 类型 thinking / tool_call / tool_result /
 * response / done）。Consumer 通过 Router 下发的 Agent SSE URL 映射
 * （{@code general=http://.../general/stream/chat}）访问本端点。
 * </p>
 */
@RestController
@RequestMapping("/general/stream")
@Slf4j
public class GeneralStreamController {

    private final SmartReActAgent generalChatAgent;

    public GeneralStreamController(SmartReActAgent generalChatAgent) {
        this.generalChatAgent = generalChatAgent;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<org.springframework.http.codec.ServerSentEvent<String>> streamChat(
            @RequestParam String message,
            @RequestParam(required = false, defaultValue = "true") boolean showThinking) {

        log.info("[GeneralStream] 开始流式对话: message={}, showThinking={}", message, showThinking);
        AtomicInteger step = new AtomicInteger(1);

        return Flux.create(sink -> {
            try {
                if (showThinking) {
                    sink.next(createSSEEvent("thinking", step.getAndIncrement(), "正在思考如何回答..."));
                }

                String result = generalChatAgent.execute(message);

                sink.next(createSSEEvent("response", 0, result));
                sink.next(createSSEEvent("done", 0, null));
                sink.complete();
                log.info("[GeneralStream] 流式对话完成: resultLength={}",
                        result != null ? result.length() : 0);

            } catch (Exception e) {
                log.error("[GeneralStream] 流式对话异常: {}", e.getMessage(), e);
                sink.next(createSSEEvent("error", 0, "处理失败: " + e.getMessage()));
                sink.next(createSSEEvent("done", 0, null));
                sink.complete();
            }
        });
    }

    private org.springframework.http.codec.ServerSentEvent<String> createSSEEvent(
            String type, int step, String content) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"type\":\"").append(type).append("\"");
        if (step > 0) {
            json.append(",\"step\":").append(step);
        }
        if (content != null) {
            json.append(",\"content\":\"").append(escapeJson(content)).append("\"");
        }
        json.append("}");
        return org.springframework.http.codec.ServerSentEvent.<String>builder()
                .id(String.valueOf(step))
                .event(type)
                .data(json.toString())
                .build();
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
