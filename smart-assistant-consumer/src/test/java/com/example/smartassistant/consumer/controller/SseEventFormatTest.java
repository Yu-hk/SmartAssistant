/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.common.sse.SseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SseEvent} 格式单元测试。
 * <p>
 * 验证 SSE 协议层面的事件格式，不涉及 HttpServletResponse I/O。
 * 覆盖预定义事件 + 自定义 raw 事件 + 多事件格式分隔。
 * </p>
 */
@DisplayName("SseEvent 格式测试")
class SseEventFormatTest {

    private void assertContains(String actual, String... expectedParts) {
        for (String part : expectedParts) {
            assertTrue(actual.contains(part), "输出中应包含: [" + part + "]\n实际: " + actual);
        }
    }

    @Test @DisplayName("done() → event:done + data:{\"type\":\"done\"}")
    void done_format() {
        String r = SseEvent.done().render();
        assertContains(r, "event: done", "\"type\":\"done\"");
        assertTrue(r.endsWith("\n\n"), "render 应以 \\n\\n 结尾");
    }

    @Test @DisplayName("error() → event:error + data:{\"type\":\"error\",\"message\":...}")
    void error_format() {
        String r = SseEvent.error("Connection lost").render();
        assertContains(r, "event: error", "\"type\":\"error\"", "Connection lost");
    }

    @Test @DisplayName("waiting() → event:waiting")
    void waiting_format() {
        assertContains(SseEvent.waiting().render(), "event: waiting");
    }

    @Test @DisplayName("processing() → event:processing")
    void processing_format() {
        assertContains(SseEvent.processing().render(), "event: processing");
    }

    @Test @DisplayName("routed() → 含 agent 名和置信度")
    void routed_format() {
        assertContains(SseEvent.routed("order", 0.95).render(),
                "event: routed", "order", "0.95");
    }

    @Test @DisplayName("raw(event, data) → 自定义事件类型 + 数据")
    void raw_event_format() {
        assertContains(SseEvent.raw("token_usage", "{\"total\":100}").render(),
                "event: token_usage", "{\"total\":100}");
    }

    @Test @DisplayName("render 包含 event: 和 data: 前缀")
    void render_hasFieldPrefixes() {
        String r = SseEvent.waiting().render();
        assertTrue(r.startsWith("event:") || r.contains("\nevent:"), "应包含 event: 前缀");
        assertTrue(r.startsWith("data:") || r.contains("\ndata:"), "应包含 data: 前缀");
    }

    @Test @DisplayName("timeout() → event:timeout")
    void timeout_format() {
        assertContains(SseEvent.timeout("Request timed out").render(),
                "event: timeout", "Request timed out");
    }
}
