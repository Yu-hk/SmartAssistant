package com.example.smartassistant.router.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulerConfigTest {

    @Test
    void parsesExternalAgentPrioritiesAndSkipsInvalidEntries() {
        assertEquals(Map.of("catalog", 20, "support", 8),
                SchedulerConfig.parseAgentPriorities("catalog:20,broken,support:8,bad:x"));
    }
}
