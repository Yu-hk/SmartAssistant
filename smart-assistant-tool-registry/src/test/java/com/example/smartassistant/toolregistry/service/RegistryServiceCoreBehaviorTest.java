package com.example.smartassistant.toolregistry.service;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import com.example.smartassistant.common.gateway.tool.compat.ToolCompatibilityChecker;
import com.example.smartassistant.toolregistry.model.HealthResult;
import com.example.smartassistant.toolregistry.model.ToolDependRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("RegistryService 核心状态与并发统计")
class RegistryServiceCoreBehaviorTest {

    private RegistryService registryService;

    @BeforeEach
    void setUp() {
        ToolManifestValidator manifestValidator = mock(ToolManifestValidator.class);
        doNothing().when(manifestValidator).validate(any(ToolDefinition.class));
        registryService = new RegistryService(mock(ToolCompatibilityChecker.class), manifestValidator);
        registryService.register(tool("critical-tool"));
    }

    @Test
    @Timeout(15)
    @DisplayName("同一 Agent 并发上报调用时依赖计数和健康指标不丢失")
    void concurrentCallRecordingKeepsDependencyAndHealthMetricsConsistent() throws Exception {
        int calls = 1_000;
        int workers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < calls; i++) {
                boolean success = i % 10 != 0;
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    registryService.recordCall("critical-tool", "router-agent", 10, success);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        List<ToolDependRecord> dependents = registryService.getDependents("critical-tool");
        assertEquals(1, dependents.size());
        assertEquals(calls, dependents.getFirst().getCallCount30d());

        HealthResult health = registryService.getHealth();
        assertEquals(1, health.getTotal());
        assertEquals(1, health.getDegraded());
        HealthResult.ToolHealthItem item = health.getTools().getFirst();
        assertEquals("critical-tool", item.getName());
        assertEquals("DEGRADED", item.getStatus());
        assertEquals(0.10, item.getErrorRate(), 0.001);
        assertEquals(10, item.getAvgLatencyMs());
    }

    @Test
    @DisplayName("废弃、重新启用和移除构成一致的工具生命周期")
    void lifecycleTransitionsUpdateStatusAndRemoveAssociatedState() {
        registryService.recordCall("critical-tool", "router-agent", 12, true);

        assertTrue(registryService.deprecate(
                "critical-tool", "replacement-tool", "2026-12-31", "migration"));
        ToolDefinition deprecated = registryService.get("critical-tool").orElseThrow();
        assertEquals(ToolStatus.DEPRECATED, deprecated.getStatus());
        assertEquals("replacement-tool", deprecated.getDeprecatedBy());
        assertEquals("2026-12-31", deprecated.getSunsetDate());

        assertTrue(registryService.activate("critical-tool"));
        ToolDefinition active = registryService.get("critical-tool").orElseThrow();
        assertEquals(ToolStatus.ACTIVE, active.getStatus());
        assertNull(active.getDeprecatedBy());
        assertNull(active.getSunsetDate());

        assertTrue(registryService.remove("critical-tool"));
        assertFalse(registryService.isRegistered("critical-tool"));
        assertTrue(registryService.getDependents("critical-tool").isEmpty());
        assertEquals(0, registryService.getHealth().getTotal());
        assertFalse(registryService.heartbeat("critical-tool"));
        assertFalse(registryService.remove("critical-tool"));
    }

    private ToolDefinition tool(String name) {
        return ToolDefinition.builder()
                .name(name)
                .description("critical business tool")
                .version("1.0.0")
                .riskLevel(ToolRiskLevel.READ)
                .status(ToolStatus.ACTIVE)
                .build();
    }
}
