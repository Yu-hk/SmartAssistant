/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.example.smartassistant.common.agent.SmartReActAgent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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

    private final SmartReActAgent orderAgent;

    public StreamingTravelAgentService(SmartReActAgent orderAgent) {
        this.orderAgent = orderAgent;
    }

    /**
     * 流式对话，返回推理过程事件流
     * <p>
     * 使用 {@link SmartReActAgent#execute(String)} 执行推理，
     * 因 {@code SmartReActAgent} 为同步执行器，流式结果仅包含最终回复事件。
     *
     * @param userMessage  用户消息
     * @param showThinking 是否显示思考过程（SmartReActAgent 同步模式不产生流式思考事件）
     * @return SSE 事件流
     */
    public Flux<ThinkingEvent> streamWithThinking(String userMessage, boolean showThinking) {
        log.info("[StreamingAgent] 开始推理: message={}, showThinking={}", userMessage, showThinking);

        return Flux.create(sink -> {
            try {
                String result = orderAgent.execute(userMessage);
                if (result != null && !result.isEmpty()) {
                    log.info("[StreamingAgent] 推理完成，回复长度: {}", result.length());
                    sink.next(ThinkingEvent.response(result));
                }
                sink.next(ThinkingEvent.done());
                sink.complete();
            } catch (Exception e) {
                String msg = e.getMessage();
                log.error("[StreamingAgent] 推理异常: {}", msg, e);
                if (msg != null && (msg.contains("image") || msg.contains("png") || msg.contains("jpg") || msg.contains("does not support"))) {
                    sink.next(ThinkingEvent.error("当前模型不支持图片输入，请避免在问题中包含图片引用"));
                } else {
                    sink.next(ThinkingEvent.error(msg != null ? msg : "处理异常"));
                }
                sink.next(ThinkingEvent.done());
                sink.complete();
            }
        });
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

        /**
         * 截断字符串
         */
        private static String truncate(String str, int maxLen) {
            if (str == null || str.length() <= maxLen) return str;
            return str.substring(0, maxLen) + "...";
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
