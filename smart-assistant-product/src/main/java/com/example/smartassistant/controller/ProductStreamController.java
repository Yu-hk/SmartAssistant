/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.service.agent.StreamingProductAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Food 服务流式响应控制器
 * <p>
 * 提供 SSE 流式输出，实时展示 AI 推理过程
 * <p>
 * SSE 事件类型：
 * - event: thinking  - AI 思考过程
 * - event: tool_call - 工具调用请求
 * - event: tool_result - 工具执行结果
 * - event: response   - 最终回复
 * - event: done       - 完成信号
 */
@RestController
@RequestMapping("/food/stream")
@Slf4j
public class ProductStreamController {

    private final StreamingProductAgentService streamingAgentService;

    public ProductStreamController(StreamingProductAgentService streamingAgentService) {
        this.streamingAgentService = streamingAgentService;
    }

    /**
     * SSE 流式对话接口
     * <p>
     * 支持实时展示 AI 推理过程
     *
     * @param message      用户消息
     * @param showThinking 是否显示思考过程（默认 true）
     * @return SSE 事件流
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<org.springframework.http.codec.ServerSentEvent<String>> streamChat(
            @RequestParam String message,
            @RequestParam(required = false, defaultValue = "true") boolean showThinking) {

        log.info("[FoodStream] 开始流式对话: message={}, showThinking={}", message, showThinking);

        AtomicInteger step = new AtomicInteger(1);

        return Flux.create(sink -> {
            try {
                // 1. 发送 thinking 事件（模拟推理开始）
                if (showThinking) {
                    sink.next(createSSEEvent("thinking", step.getAndIncrement(), "正在分析用户需求..."));
                }

                // 2. 发送 tool_call 事件（模拟工具调用）
                sink.next(createSSEEvent("tool_call", step.getAndIncrement(), null, "querySpecialtyCuisine", null));

                // 3. 发送 tool_result 事件
                sink.next(createSSEEvent("tool_result", 0, "正在查询美食数据库..."));

                // 4. 执行实际推理
                String result = streamingAgentService.execute(message);

                // 5. 发送最终回复
                sink.next(createSSEEvent("response", 0, result));

                // 6. 发送完成信号
                sink.next(createSSEEvent("done", 0, null));

                sink.complete();
                log.info("[FoodStream] 流式对话完成");

            } catch (Exception e) {
                log.error("[FoodStream] 流式对话异常: {}", e.getMessage(), e);
                sink.next(createSSEEvent("error", 0, "处理失败: " + e.getMessage()));
                sink.next(createSSEEvent("done", 0, null));
                sink.complete();
            }
        });
    }

    /**
     * 简单的非流式对话（兼容旧接口）
     */
    @PostMapping("/chat/sync")
    public String chatSync(@RequestParam String message) {
        log.info("[FoodStream] 同步对话: {}", message);
        return streamingAgentService.execute(message);
    }

    /**
     * 创建 SSE 事件
     */
    private org.springframework.http.codec.ServerSentEvent<String> createSSEEvent(
            String type, int step, String content) {
        return createSSEEvent(type, step, content, null, null);
    }

    private org.springframework.http.codec.ServerSentEvent<String> createSSEEvent(
            String type, int step, String content, String toolName, String arguments) {

        StringBuilder json = new StringBuilder("{");
        json.append("\"type\":\"").append(type).append("\"");

        if (step > 0) {
            json.append(",\"step\":").append(step);
        }
        if (content != null) {
            json.append(",\"content\":\"").append(escapeJson(content)).append("\"");
        }
        if (toolName != null) {
            json.append(",\"toolName\":\"").append(toolName).append("\"");
        }
        if (arguments != null) {
            json.append(",\"arguments\":\"").append(escapeJson(arguments)).append("\"");
        }

        json.append("}");

        return org.springframework.http.codec.ServerSentEvent.<String>builder()
                .id(String.valueOf(step))
                .event(type)
                .data(json.toString())
                .build();
    }

    /**
     * 转义 JSON 特殊字符
     */
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
