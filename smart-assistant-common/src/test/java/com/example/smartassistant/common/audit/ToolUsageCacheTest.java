package com.example.smartassistant.common.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolUsageCacheTest {

    @AfterEach
    void cleanup() {
        ToolUsageCache.clear();
    }

    @Test
    void distinguishesMeasuredNoToolsFromUnknown() {
        assertNull(ToolUsageCache.consume("missing"));
        ToolUsageCache.start("request-1");

        ToolUsageCache.ToolUsage usage = ToolUsageCache.consume("request-1");

        assertNotNull(usage);
        assertTrue(usage.complete());
        assertTrue(usage.calls().isEmpty());
    }

    @Test
    void mergesCallsAndPreservesIncompleteState() {
        ToolUsageCache.start("request-2");
        ToolUsageCache.record("request-2", "queryWeather", true, 25);
        ToolUsageCache.merge("request-2", new ToolUsageCache.ToolUsage(false, List.of(
                new ToolUsageCache.ToolCall("webSearch", "FAILED", 80))));

        ToolUsageCache.ToolUsage usage = ToolUsageCache.consume("request-2");

        assertNotNull(usage);
        assertFalse(usage.complete());
        assertEquals(2, usage.calls().size());
        assertEquals("queryWeather", usage.calls().getFirst().name());
        assertEquals("FAILED", usage.calls().getLast().status());
    }

    @Test
    void headerCodecRoundTripsEmptyAndPopulatedUsage() {
        ToolUsageCache.ToolUsage original = new ToolUsageCache.ToolUsage(true, List.of(
                new ToolUsageCache.ToolCall("queryOrder", "SUCCESS", 12)));

        ToolUsageCache.ToolUsage decoded = ToolUsageHeaders.decode(ToolUsageHeaders.encode(original));

        assertEquals(original, decoded);
        assertEquals(new ToolUsageCache.ToolUsage(true, List.of()),
                ToolUsageHeaders.decode(ToolUsageHeaders.encode(
                        new ToolUsageCache.ToolUsage(true, List.of()))));
    }
}
