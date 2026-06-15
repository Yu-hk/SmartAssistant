/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.example.smartassistant.common.agent.ReActObserver;
import com.example.smartassistant.common.agent.SmartReActAgent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式旅行 Agent 服务
 * <p>
 * 基于 {@link SmartReActAgent} + {@link ReActObserver} 实现，
 * 不再依赖 Alibaba {@code ReactAgent.stream()}。
 * </p>
 * <p>
 * 事件类型：
 * <ul>
 *   <li>thinking: AI 思考过程（完整推理文本）</li>
 *   <li>tool_call: 工具调用请求</li>
 *   <li>tool_result: 工具执行结果</li>
 *   <li>response: 最终回复</li>
 *   <li>error: 错误</li>
 *   <li>done: 完成信号</li>
 * </ul>
 * </p>
 */
@Service
@Slf4j
public class StreamingTravelAgentService {

    private final SmartReActAgent orderAgent;

    public StreamingTravelAgentService(SmartReActAgent orderAgent) {
        this.orderAgent = orderAgent;
    }

    /**
     * 流式对话，返回推理过程事件流。
     * <p>
     * 通过 {@link ReActObserver} 监听 {@link SmartReActAgent#execute(String)} 的
     * 各执行阶段，将同步执行过程转换为异步事件流。
     *
     * @param userMessage  用户消息
     * @param showThinking 是否显示推理过程
     * @return SSE 事件流（在独立线程上消费）
     */
    public Flux<ThinkingEvent> streamWithThinking(String userMessage, boolean showThinking) {
        log.info("[StreamingAgent] 开始流式推理: message={}, showThinking={}", userMessage, showThinking);

        final AtomicInteger toolSeq = new AtomicInteger(1);

        return Flux.create(sink -> {
            AtomicInteger iteration = new AtomicInteger(1);
            AtomicBoolean responseEmitted = new AtomicBoolean(false);

            // ⭐ 注册观察者，将执行阶段映射为 ThinkingEvent
            ReActObserver observer = new ReActObserver() {
                @Override
                public void onThinking(int step, String reasoningContent) {
                    if (showThinking) {
                        log.debug("[StreamingAgent] 推理过程[{}]: {}", step,
                                truncate(reasoningContent, 100));
                        sink.next(ThinkingEvent.thinking(step, reasoningContent));
                    }
                }

                @Override
                public void onToolCall(int step, String toolName, String arguments) {
                    log.info("[StreamingAgent] 工具调用[{}]: {}", step, toolName);
                    sink.next(ThinkingEvent.toolCall(step, toolName, arguments));
                }

                @Override
                public void onToolResult(String content) {
                    log.debug("[StreamingAgent] 工具结果: {}", truncate(content, 100));
                    sink.next(ThinkingEvent.toolResult(content));
                }

                @Override
                public void onResponse(String response) {
                    responseEmitted.set(true);
                    log.info("[StreamingAgent] 最终回复长度: {}", response.length());
                    sink.next(ThinkingEvent.response(response));
                }
            };

            try {
                // ⭐ 使用 observer 执行同步 ReAct 循环
                String result = orderAgent.withObserver(observer).execute(userMessage);

                // 如果 onResponse 未被调用（例如 maxIterations 耗尽导致提前返回），则从返回值发送
                if (!responseEmitted.get() && result != null && !result.isEmpty()) {
                    log.info("[StreamingAgent] 从返回值发送回复 (onResponse 未触发)");
                    sink.next(ThinkingEvent.response(result));
                }
            } catch (Exception e) {
                String errMsg = e.getMessage();
                // ⭐ 拦截模型不支持图片的错误
                if (errMsg != null && (errMsg.contains("image") || errMsg.contains("png")
                        || errMsg.contains("jpg") || errMsg.contains("does not support"))) {
                    errMsg = "当前模型不支持图片输入，请避免在问题中包含图片引用";
                    log.warn("[StreamingAgent] 拦截图片输入错误");
                }
                log.error("[StreamingAgent] 流式推理异常: {}", errMsg, e);
                sink.next(ThinkingEvent.error(errMsg != null ? errMsg : "处理异常"));
            } finally {
                sink.next(ThinkingEvent.done());
                sink.complete();
            }
        });
    }

    /**
     * 截断字符串。
     */
    private static String truncate(String str, int maxLen) {
        if (str == null || str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }

    // ==================== ThinkingEvent ====================

    /**
     * 推理过程事件。
     * <p>
     * 与旧版保持字段兼容，下游 {@code TravelStreamController} 无需修改。
     */
    @Getter
    public static class ThinkingEvent {
        private final EventType type;
        private final int step;
        private final String content;
        private final String toolName;
        private final String arguments;

        public enum EventType {
            THINKING,
            TOOL_CALL,
            TOOL_RESULT,
            RESPONSE,
            ERROR,
            DONE
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
         * 转换为 SSE 数据格式（JSON）。
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

        private static String escapeJson(String str) {
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
