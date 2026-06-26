/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.trace;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TraceSpan} 单元测试。
 */
class TraceSpanTest {

    @Test
    void noopRegistry_silentlyWorks() {
        String result = TraceSpan.of(ObservationRegistry.NOOP, "test-span")
                .run(() -> "done");
        assertEquals("done", result);
    }

    @Test
    void noopRegistry_runnable() {
        TraceSpan.of(ObservationRegistry.NOOP, "test-span")
                .run(() -> { /* no-op */ });
        // should not throw
    }

    @Test
    void nullRegistry_silentlyWorks() {
        String result = TraceSpan.of(null, "test-span")
                .run(() -> "ok");
        assertEquals("ok", result);
    }
}
