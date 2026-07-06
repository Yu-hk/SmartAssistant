package com.example.smartassistant.common.sse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SseEvent} 单元测试。
 */
class SseEventTest {

    @Test
    @DisplayName("渲染基本 SSE 事件")
    void renderBasicEvent() {
        String rendered = SseEvent.create()
                .event("thinking")
                .data("{\"content\":\"思考中...\"}")
                .render();
        assertTrue(rendered.contains("event: thinking"));
        assertTrue(rendered.contains("data: {\"content\":\"思考中...\"}"));
        assertTrue(rendered.endsWith("\n\n"));
    }

    @Test
    @DisplayName("带 ID 的事件")
    void eventWithId() {
        String rendered = SseEvent.create()
                .id("42")
                .event("response")
                .data("hello")
                .render();
        assertTrue(rendered.contains("id: 42"));
        assertTrue(rendered.startsWith("id:"));
    }

    @Test
    @DisplayName("waiting 预定义事件")
    void waitingEvent() {
        String rendered = SseEvent.waiting().render();
        assertTrue(rendered.contains("event: waiting"));
        assertTrue(rendered.contains("正在分析意图"));
    }

    @Test
    @DisplayName("done 预定义事件")
    void doneEvent() {
        String rendered = SseEvent.done().render();
        assertTrue(rendered.contains("event: done"));
        assertTrue(rendered.contains("\"type\":\"done\""));
    }

    @Test
    @DisplayName("error 预定义事件")
    void errorEvent() {
        String rendered = SseEvent.error("发生错误").render();
        assertTrue(rendered.contains("event: error"));
        assertTrue(rendered.contains("发生错误"));
    }

    @Test
    @DisplayName("queue 预定义事件")
    void queueEvent() {
        String rendered = SseEvent.queue(3, 15000).render();
        assertTrue(rendered.contains("event: queued"));
        assertTrue(rendered.contains("\"position\":3"));
        assertTrue(rendered.contains("\"estimatedWaitMs\":15000"));
    }

    @Test
    @DisplayName("routed 预定义事件")
    void routedEvent() {
        String rendered = SseEvent.routed("order-service", 0.95).render();
        assertTrue(rendered.contains("event: routed"));
        assertTrue(rendered.contains("order-service"));
        assertTrue(rendered.contains("0.95"));
    }
}
