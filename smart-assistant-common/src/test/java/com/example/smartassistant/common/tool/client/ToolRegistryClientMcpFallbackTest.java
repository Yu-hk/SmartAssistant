package com.example.smartassistant.common.tool.client;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link ToolRegistryClient} MCP 优先发现 + REST fallback 单元测试（T2c-4 预留桩）。
 *
 * <p>T2c 的 MCP 优先通道（McpRegistryDiscoveryClient 注入 + fetchFromRegistryRest + mergeByEndpoint）
 * 需在 T2c 实现完成后启用。当前测试仅验证基础降级路径（开关关闭时纯 REST）。</p>
 */
class ToolRegistryClientMcpFallbackTest {

    private static ToolDefinition sampleRestDef(String name, String endpoint) {
        return ToolDefinition.builder()
                .name(name)
                .description("rest desc")
                .endpoint(endpoint)
                .inputSchema("{}")
                .toolTier(ToolTier.SHARED)
                .riskLevel(ToolRiskLevel.READ)
                .build();
    }

    @Test
    void getToolDefinitionsWithDefaultConfigShouldReturnRestResults() {
        ToolRegistryProperties props = new ToolRegistryProperties();
        props.setMcpDiscoveryEnabled(false);

        ToolRegistryClient client = new ToolRegistryClient(props, new ObjectMapper(),
                mock(ToolGateway.class), new ToolRegistry());

        // 当前实现走 REST 路径（无需 MCP 注入）；Registry 不可用时返回空
        List<ToolDefinition> result = client.getToolDefinitions("ORDER");
        assertNotNull(result);
        // Registry 不可用 → 缓存未命中 → 远程失败 → 返回空
        assertTrue(result.isEmpty());
    }

    @Test
    void switchOffShouldNotUseMcp() {
        ToolRegistryProperties props = new ToolRegistryProperties();
        props.setMcpDiscoveryEnabled(false);

        // 即使不注入 discoveryClient，开关关闭也应正常工作
        ToolRegistryClient client = new ToolRegistryClient(props, new ObjectMapper(),
                mock(ToolGateway.class), new ToolRegistry());

        List<ToolDefinition> result = client.getToolDefinitions("ORDER");
        assertNotNull(result);
    }
}
