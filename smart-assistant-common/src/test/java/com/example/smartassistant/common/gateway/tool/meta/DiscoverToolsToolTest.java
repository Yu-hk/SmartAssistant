package com.example.smartassistant.common.gateway.tool.meta;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.example.smartassistant.common.gateway.tool.mcp.McpRegistryDiscoveryClient;
import com.example.smartassistant.common.gateway.tool.mcp.McpToolCallbackFactory;
import com.example.smartassistant.common.gateway.tool.mcp.McpRegistryDiscoveryClient.McpSearchFilter;
import com.example.smartassistant.common.tool.client.ToolRegistryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link DiscoverToolsTool} 单元测试（T2d）。
 *
 * <ul>
 *   <li>护栏：已发现能力去重 / 每会话上限 / 动态工具上限</li>
 *   <li>正常发现流程：调 {@link McpRegistryDiscoveryClient#searchTools} → 组装 → 注册</li>
 *   <li>降级：Registry 不可用时返回仅预载可用</li>
 *   <li>治理包裹：发现工具经 {@link com.example.smartassistant.common.gateway.tool.ToolGatewayToolCallback}（def 感知重载）</li>
 * </ul>
 */
class DiscoverToolsToolTest {

    private McpRegistryDiscoveryClient discoveryClient;
    private McpToolCallbackFactory callbackFactory;
    private ToolRegistryProperties properties;
    private ObjectMapper objectMapper;
    private DiscoverToolsTool tool;
    private List<ToolCallback> registeredCallbacks;

    @BeforeEach
    void setUp() {
        discoveryClient = mock(McpRegistryDiscoveryClient.class);
        callbackFactory = mock(McpToolCallbackFactory.class);
        properties = new ToolRegistryProperties();
        properties.setT2McpDiscoveryEnabled(true);
        objectMapper = new ObjectMapper();
        registeredCallbacks = new ArrayList<>();

        tool = new DiscoverToolsTool(discoveryClient, callbackFactory, properties, objectMapper, null);
        tool.setToolRegistrar(callbacks -> registeredCallbacks.addAll(callbacks));
    }

    // ==================== 护栏测试 ====================

    @Test
    void shouldReturnCacheWhenSameCapabilityDiscoveredTwice() throws Exception {
        ToolDefinition def = sampleDef();
        when(discoveryClient.searchTools(any(McpSearchFilter.class))).thenReturn(List.of(def));
        ToolCallback mockCb = mockToolCallback("consumer.executeQuery");

        when(callbackFactory.create(any(ToolDefinition.class), any())).thenReturn(mockCb);

        // 第一次发现 - 应该成功
        String firstResult = tool.discoverTools("sql-query", null, "OR", 20);
        assertTrue(firstResult.contains("sql-query"));
        assertTrue(firstResult.contains("\"source\":\"registry\""));
        assertEquals(1, registeredCallbacks.size());

        // 第二次发现相同能力 - 应该返回缓存
        String secondResult = tool.discoverTools("sql-query", null, "OR", 20);
        assertTrue(secondResult.contains("\"source\":\"cache\""));
        // 不应重复注册
        assertEquals(1, registeredCallbacks.size());
    }

    @Test
    void shouldRejectWhenExceedingMaxDiscoveriesPerSession() throws Exception {
        properties.setMaxDiscoveriesPerSession(2);

        ToolDefinition def = sampleDef();
        when(discoveryClient.searchTools(any(McpSearchFilter.class))).thenReturn(List.of(def));
        ToolCallback mockCb = mockToolCallback("consumer.executeQuery");
        when(callbackFactory.create(any(ToolDefinition.class), any())).thenReturn(mockCb);

        // 第一次发现
        tool.discoverTools("sql-query", null, "OR", 20);
        // 第二次发现（不同能力）
        tool.discoverTools("weather-query", null, "OR", 20);

        // 第三次发现 - 应该被拒绝
        String thirdResult = tool.discoverTools("refund", null, "OR", 20);
        assertTrue(thirdResult.contains("已达到每会话发现上限"));
        assertEquals(2, registeredCallbacks.size());
    }

    @Test
    void shouldEnforceMaxDynamicToolsLimit() throws Exception {
        properties.setMaxDynamicTools(1);

        ToolDefinition def1 = ToolDefinition.builder()
                .name("tool.a").description("a")
                .endpoint("http://e:1").inputSchema("{}")
                .toolTier(ToolTier.SHARED).riskLevel(ToolRiskLevel.READ)
                .build();
        ToolDefinition def2 = ToolDefinition.builder()
                .name("tool.b").description("b")
                .endpoint("http://e:2").inputSchema("{}")
                .toolTier(ToolTier.SHARED).riskLevel(ToolRiskLevel.READ)
                .build();

        when(discoveryClient.searchTools(any(McpSearchFilter.class))).thenReturn(List.of(def1, def2));
        ToolCallback mockCb1 = mockToolCallback("tool.a");
        ToolCallback mockCb2 = mockToolCallback("tool.b");
        when(callbackFactory.create(eq(def1), any())).thenReturn(mockCb1);
        when(callbackFactory.create(eq(def2), any())).thenReturn(mockCb2);

        String result = tool.discoverTools("test", null, "OR", 20);
        // 只应注入 1 个工具（上限为 1）
        assertEquals(1, registeredCallbacks.size());
        assertTrue(result.contains("tool.a"));
    }

    // ==================== 正常发现流程 ====================

    @Test
    void shouldDiscoverToolsAndRegisterCallbacks() throws Exception {
        ToolDefinition def = sampleDef();
        when(discoveryClient.searchTools(any(McpSearchFilter.class))).thenReturn(List.of(def));
        ToolCallback mockCb = mockToolCallback("consumer.executeQuery");
        when(callbackFactory.create(eq(def), any())).thenReturn(mockCb);

        String result = tool.discoverTools("sql-query", new String[]{"sql"}, "OR", 10);

        // 验证搜索结果
        assertTrue(result.contains("\"query\":\"sql-query\""));
        assertTrue(result.contains("\"source\":\"registry\""));
        assertTrue(result.contains("consumer.executeQuery"));

        // 验证注册
        assertEquals(1, registeredCallbacks.size());
        assertEquals("consumer.executeQuery", registeredCallbacks.get(0).getToolDefinition().name());

        // 验证 discoveryClient 被调用
        verify(discoveryClient).searchTools(any(McpSearchFilter.class));
        // 验证 callbackFactory 被调用（def 感知重载）
        verify(callbackFactory).create(eq(def), any());
    }

    // ==================== 降级测试 ====================

    @Test
    void shouldDegradeGracefullyWhenRegistryUnavailable() throws Exception {
        when(discoveryClient.searchTools(any(McpSearchFilter.class)))
                .thenThrow(new RuntimeException("connection refused"));

        String result = tool.discoverTools("sql-query", null, "OR", 20);

        // 降级返回，不抛异常
        assertTrue(result.contains("\"source\":\"registry-unavailable\""));
        assertTrue(result.contains("sql-query"));
        // 不应有任何注册
        assertEquals(0, registeredCallbacks.size());
    }

    @Test
    void shouldNotPreRegisterDiscoverToolsTool() throws Exception {
        // 验证新创建的 tool 没有任何预注册能力
        assertTrue(tool.getDiscoveredCapabilityHistory().isEmpty());
        assertEquals(0, tool.getDiscoveryCount());
    }

    // ==================== 辅助方法 ====================

    private ToolDefinition sampleDef() {
        return ToolDefinition.builder()
                .name("consumer.executeQuery")
                .description("执行只读 SQL 查询")
                .endpoint("http://backend:8081/mcp")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}}}")
                .toolTier(ToolTier.SHARED)
                .riskLevel(ToolRiskLevel.READ)
                .build();
    }

    private ToolCallback mockToolCallback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(
                DefaultToolDefinition.builder().name(name).description("test").inputSchema("{}").build());
        when(cb.call(anyString())).thenReturn("mock-result");
        return cb;
    }
}
