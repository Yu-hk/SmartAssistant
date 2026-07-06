/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.sse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 事件——结构化构建。
 *
 * <p>参考 SSE 协议（text/event-stream）格式：
 * <pre>
 * id: 1
 * event: thinking
 * data: {"type":"thinking","content":"..."}
 * </pre>
 */
public class SseEvent {

    private final Map<String, String> fields = new LinkedHashMap<>();

    private SseEvent() {}

    public static SseEvent create() {
        return new SseEvent();
    }

    public SseEvent id(String id) { fields.put("id", id); return this; }
    public SseEvent event(String event) { fields.put("event", event); return this; }
    public SseEvent data(String data) { fields.put("data", data); return this; }
    public SseEvent retry(long ms) { fields.put("retry", String.valueOf(ms)); return this; }
    public SseEvent comment(String comment) { return this; }

    /**
     * 渲染为 SSE 协议文本（以 \n\n 结尾）。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    // ==================== 预定义事件工厂 ====================

    public static SseEvent waiting() {
        return create().event("waiting").data("{\"type\":\"waiting\",\"content\":\"正在分析意图...\"}");
    }

    public static SseEvent processing() {
        return create().event("processing").data("{\"type\":\"processing\"}");
    }

    public static SseEvent done() {
        return create().event("done").data("{\"type\":\"done\"}");
    }

    public static SseEvent error(String message) {
        return create().event("error").data(
                "{\"type\":\"error\",\"content\":\"" + escape(message) + "\"}");
    }

    public static SseEvent timeout(String message) {
        return create().event("timeout").data(
                "{\"type\":\"timeout\",\"content\":\"" + escape(message) + "\"}");
    }

    public static SseEvent queue(int position, long estimatedWaitMs) {
        return create().event("queued").data(
                "{\"type\":\"queued\",\"position\":" + position
                        + ",\"estimatedWaitMs\":" + estimatedWaitMs + "}");
    }

    public static SseEvent queuePosition(int position, long estimatedWaitMs) {
        return create().event("queue_position").data(
                "{\"type\":\"queue_position\",\"position\":" + position
                        + ",\"estimatedWaitMs\":" + estimatedWaitMs + "}");
    }

    public static SseEvent routed(String agentName, double confidence) {
        return create().event("routed").data(
                "{\"type\":\"routed\",\"agentName\":\"" + escape(agentName)
                        + "\",\"confidence\":" + confidence + "}");
    }

    public static SseEvent raw(String eventName, String jsonData) {
        return create().event(eventName).data(jsonData);
    }

    private static String escape(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
