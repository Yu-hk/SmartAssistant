/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式旅行 Agent 服务
 * <p>
 * 提供实时推理过程输出，支持前端实时展示 AI 思考流程
 * <p>
 * 事件类型：
 * - thinking: AI 思考过程（模型推理）
 * - tool_call: 工具调用请求
 * - tool_result: 工具执行结果
 * - response: 最终回复
 * - done: 完成信号
 */
@Service
@Slf4j
public class StreamingTravelAgentService {

    private final ReactAgent locationWeatherAgent;

    public StreamingTravelAgentService(ReactAgent locationWeatherAgent) {
        this.locationWeatherAgent = locationWeatherAgent;
    }

    /**
     * 流式对话，返回推理过程事件流
     *
     * @param userMessage  用户消息
     * @param showThinking 是否显示思考过程
     * @return SSE 事件流
     */
    public Flux<ThinkingEvent> streamWithThinking(String userMessage, boolean showThinking) {
        log.info("[StreamingAgent] 开始流式推理: message={}, showThinking={}", userMessage, showThinking);

        Flux<NodeOutput> outputFlux;
        try {
            outputFlux = locationWeatherAgent.stream(userMessage);
        } catch (Exception e) {
            log.error("[StreamingAgent] 启动流式推理失败: {}", e.getMessage(), e);
            return Flux.just(ThinkingEvent.error("启动流式推理失败: " + e.getMessage()), ThinkingEvent.done());
        }

        AtomicInteger stepCounter = new AtomicInteger(1);
        AtomicInteger toolCounter = new AtomicInteger(1);
        StringBuilder responseBuilder = new StringBuilder();

        return outputFlux
                .filter(nodeOutput -> nodeOutput instanceof StreamingOutput)
                .map(nodeOutput -> (StreamingOutput) nodeOutput)
                .mapNotNull(streamingOutput -> {
                    try {
                        return processStreamingOutput(streamingOutput, stepCounter, toolCounter,
                                showThinking, responseBuilder);
                    } catch (Exception e) {
                        log.error("[StreamingAgent] 处理输出异常: {}", e.getMessage(), e);
                        return ThinkingEvent.error(e.getMessage());
                    }
                })
                .filter(Objects::nonNull) // 过滤空事件
                .doOnComplete(() -> {
                    String finalResponse = responseBuilder.toString();
                    if (!finalResponse.isEmpty()) {
                        log.info("[StreamingAgent] 推理完成，最终回复长度: {}", finalResponse.length());
                    }
                    // ⭐ 如果最终响应事件为空，发送完成信号
                    if (!responseBuilder.isEmpty()) {
                        log.info("[StreamingAgent] 发送完成信号");
                    }
                })
                // ⭐ 确保发送完成信号
                .concatWith(Flux.just(ThinkingEvent.done()))
                .doOnError(e -> log.error("[StreamingAgent] 流式推理异常: {}", e.getMessage(), e))
                .onErrorResume(e -> {
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("image") || msg.contains("png") || msg.contains("jpg") || msg.contains("does not support"))) {
                        log.error("[StreamingAgent] 模型不支持图片输入，已拦截: {}", msg);
                        return Flux.just(
                            ThinkingEvent.error("当前模型不支持图片输入，请避免在问题中包含图片引用"),
                            ThinkingEvent.done()
                        );
                    }
                    return Flux.just(ThinkingEvent.error(msg != null ? msg : "处理异常"), ThinkingEvent.done());
                });
    }

    /**
     * 处理流式输出，转换为 ThinkingEvent
     */
    private ThinkingEvent processStreamingOutput(
            StreamingOutput output,
            AtomicInteger stepCounter,
            AtomicInteger toolCounter,
            boolean showThinking,
            StringBuilder responseBuilder) {

        String outputType = output.getOutputType().name();
        Message message = output.message();

        // 模型流式输出（思考过程）
        switch (outputType) {
            case "AGENT_MODEL_STREAMING" -> {
                if (message instanceof AssistantMessage assistantMsg) {
                    // 获取推理内容
                    Object reasoningContent = assistantMsg.getMetadata().get("reasoningContent");
                    if (reasoningContent != null && !reasoningContent.toString().isEmpty() && showThinking) {
                        String thinking = reasoningContent.toString();
                        log.debug("[StreamingAgent] 思考过程[{}]: {}", stepCounter.get(), truncate(thinking, 100));
                        return ThinkingEvent.thinking(stepCounter.getAndIncrement(), thinking);
                    }

                    // 普通回复内容（累积到 responseBuilder）
                    String text = assistantMsg.getText();
                    if (text != null && !text.isEmpty()) {
                        responseBuilder.append(text);
                    }
                }
            }
            // 模型推理完成，检查工具调用
            case "AGENT_MODEL_FINISHED" -> {
                if (message instanceof AssistantMessage assistantMsg) {
                    if (assistantMsg.hasToolCalls()) {
                        for (ToolCall toolCall : assistantMsg.getToolCalls()) {
                            int step = toolCounter.getAndIncrement();
                            log.info("[StreamingAgent] 工具调用[{}]: {}", step, toolCall.name());
                            return ThinkingEvent.toolCall(step, toolCall.name(), toolCall.arguments());
                        }
                    }

                    // 完整回复内容
                    String fullText = assistantMsg.getText();
                    if (fullText != null && !fullText.isEmpty()) {
                        responseBuilder.append(fullText);
                        return ThinkingEvent.response(fullText);
                    }
                }
            }
            // 工具执行完成
            case "AGENT_TOOL_FINISHED" -> {
                if (message instanceof ToolResponseMessage toolResponse) {
                    StringBuilder results = new StringBuilder();
                    toolResponse.getResponses().forEach(response -> {
                        String result = response.responseData();
                        if (result.length() > 2000) {
                            result = result.substring(0, 2000) + "...";
                        }
                        results.append(response.name()).append(": ").append(result);
                    });
                    String resultStr = results.toString();
                    log.debug("[StreamingAgent] 工具结果: {}", truncate(resultStr, 100));
                    return ThinkingEvent.toolResult(resultStr);
                }
            }
        }

        return null; // 无需处理的事件
    }

    /**
     * 截断字符串
     */
    private static String truncate(String str, int maxLen) {
        if (str == null || str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }

    /**
     * 推理过程事件
     */
    @Getter
    public static class ThinkingEvent {
        private final EventType type;
        private final int step; // 步骤编号（用于排序）
        private final String content; // 事件内容
        private final String toolName; // 工具名称（仅 tool_call 类型）
        private final String arguments; // 工具参数（仅 tool_call 类型）

        public enum EventType {
            THINKING,   // 思考过程
            TOOL_CALL, // 工具调用
            TOOL_RESULT, // 工具结果
            RESPONSE,  // 最终回复
            ERROR,     // 错误
            DONE       // 完成
        }

        private ThinkingEvent(EventType type, int step, String content, String toolName, String arguments) {
            this.type = type;
            this.step = step;
            this.content = content;
            this.toolName = toolName;
            this.arguments = arguments;
        }

        public static ThinkingEvent thinking(int step, String content) {
            return new ThinkingEvent(EventType.THINKING, step, content, null, null);
        }

        public static ThinkingEvent toolCall(int step, String toolName, String arguments) {
            return new ThinkingEvent(EventType.TOOL_CALL, step, null, toolName, arguments);
        }

        public static ThinkingEvent toolResult(String content) {
            return new ThinkingEvent(EventType.TOOL_RESULT, 0, content, null, null);
        }

        public static ThinkingEvent response(String content) {
            return new ThinkingEvent(EventType.RESPONSE, 0, content, null, null);
        }

        public static ThinkingEvent error(String message) {
            return new ThinkingEvent(EventType.ERROR, 0, message, null, null);
        }

        public static ThinkingEvent done() {
            return new ThinkingEvent(EventType.DONE, 0, null, null, null);
        }

        @Override
        public String toString() {
            return switch (type) {
                case THINKING -> String.format("[思考 %d] %s", step, truncate(content, 80));
                case TOOL_CALL -> String.format("[工具 %d] %s(%s)", step, toolName, truncate(arguments, 50));
                case TOOL_RESULT -> String.format("[结果] %s", truncate(content, 80));
                case RESPONSE -> String.format("[回复] %s", truncate(content, 100));
                case ERROR -> String.format("[错误] %s", content);
                case DONE -> "[完成]";
            };
        }

        /**
         * 转换为 SSE 格式
         */
        public String toSseData() {
            StringBuilder json = new StringBuilder("{");
            json.append("\"type\":\"").append(type.name().toLowerCase()).append("\"");

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
            return json.toString();
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
}
