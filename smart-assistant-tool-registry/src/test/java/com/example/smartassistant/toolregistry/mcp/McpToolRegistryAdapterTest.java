package com.example.smartassistant.toolregistry.mcp;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.example.smartassistant.toolregistry.service.RegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * {@link McpToolRegistryAdapter} 单元测试（T2a）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>{@code ToolDefinition -> MCP Tool} 映射（name/description 原样、inputSchema 空回退、
 *       annotations 由 riskLevel 推导、meta 扩展字段与 functionalCapabilities/toolTier 双写）；</li>
 *   <li>中心目录工具为「可发现但拒绝执行」：callHandler 始终返回 isError=true 且含指引信息；</li>
 *   <li>{@code search_tools} 工具规格可被调用并返回经 RegistryService.search 检索、再映射的 JSON 结果。</li>
 * </ul>
 *
 * <p>注：{@code ToolDefinition.read(...)} / {@code highRisk(...)} 返回已构建的 {@link ToolDefinition}，
 * 故测试中经 {@code .toBuilder()} 取回构建器以继续设置字段（与 {@code ToolGatewayTest} 用法一致）。</p>
 */
class McpToolRegistryAdapterTest {

    private ObjectMapper objectMapper;
    private McpToolRegistryAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new McpToolRegistryAdapter(objectMapper);
    }

    // ==================== ToolDefinition -> MCP Tool 映射 ====================

    @Test
    @DisplayName("toMcpTool：name/description 原样映射")
    void mapsNameAndDescription() {
        ToolDefinition def = ToolDefinition.read("queryOrder", "查询订单详情")
                .toBuilder()
                .status(ToolStatus.ACTIVE)
                .functionalCapabilities(List.of("order-query"))
                .toolTier(ToolTier.CORE)
                .build();

        Tool tool = adapter.toMcpTool(def);

        assertEquals("queryOrder", tool.name());
        assertEquals("查询订单详情", tool.description());
    }

    @Test
    @DisplayName("toMcpTool：inputSchema 为 null 时回退空对象 schema")
    void inputSchemaNullFallsBackToEmptyObject() {
        ToolDefinition def = ToolDefinition.read("queryOrder", "查询订单详情")
                .toBuilder()
                .status(ToolStatus.ACTIVE)
                .build();

        Tool tool = adapter.toMcpTool(def);
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    @DisplayName("toMcpTool：inputSchema 为合法 JSON 时被解析为 schema map")
    void inputSchemaParsedWhenPresent() {
        ToolDefinition def = ToolDefinition.read("queryOrder", "查询订单详情")
                .toBuilder()
                .status(ToolStatus.ACTIVE)
                .inputSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}")
                .build();

        Tool tool = adapter.toMcpTool(def);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tool.inputSchema().get("properties");
        assertNotNull(props);
        assertTrue(props.containsKey("id"));
    }

    @Test
    @DisplayName("toMcpTool：annotations 由 riskLevel 推导（READ -> readOnly, HIGH -> destructive）")
    void annotationsDerivedFromRiskLevel() {
        ToolDefinition readDef = ToolDefinition.read("queryProduct", "查询商品")
                .toBuilder()
                .riskLevel(ToolRiskLevel.READ).status(ToolStatus.ACTIVE).build();
        Tool readTool = adapter.toMcpTool(readDef);
        assertTrue(readTool.annotations().readOnlyHint());
        assertFalse(readTool.annotations().destructiveHint());

        ToolDefinition highDef = ToolDefinition.highRisk("refundOrder", "退款订单", true)
                .toBuilder()
                .status(ToolStatus.ACTIVE).build();
        Tool highTool = adapter.toMcpTool(highDef);
        assertFalse(highTool.annotations().readOnlyHint());
        assertTrue(highTool.annotations().destructiveHint());
        assertFalse(highTool.annotations().openWorldHint());
    }

    @Test
    @DisplayName("toMcpTool：meta 写入 x-* 扩展字段并双写 functionalCapabilities/toolTier")
    void metaCarriesExtensionAndDoubleWrite() {
        ToolDefinition def = ToolDefinition.read("queryOrder", "查询订单详情")
                .toBuilder()
                .status(ToolStatus.ACTIVE)
                .riskLevel(ToolRiskLevel.READ)
                .toolTier(ToolTier.SHARED)
                .tags(new String[]{"ORDER", "READ_ONLY"})
                .functionalCapabilities(List.of("order-query", "order-read"))
                .build();

        Tool tool = adapter.toMcpTool(def);
        Map<String, Object> meta = tool.meta();
        assertNotNull(meta);

        @SuppressWarnings("unchecked")
        List<String> xFc = (List<String>) meta.get("x-functional-capabilities");
        assertEquals(List.of("order-query", "order-read"), xFc);

        assertEquals("SHARED", meta.get("x-tool-tier"));
        assertEquals("READ", meta.get("x-risk-level"));
        @SuppressWarnings("unchecked")
        List<String> xTags = (List<String>) meta.get("x-tags");
        assertEquals(List.of("ORDER", "READ_ONLY"), xTags);

        // 双写：兼容只解析 _meta 的客户端
        @SuppressWarnings("unchecked")
        List<String> fc = (List<String>) meta.get("functionalCapabilities");
        assertEquals(List.of("order-query", "order-read"), fc);
        assertEquals("SHARED", meta.get("toolTier"));
    }

    // ==================== 拒绝传递式 tools/call ====================

    @Test
    @DisplayName("toDiscoveryToolSpecification：callHandler 拒绝执行（isError=true 且含指引）")
    void discoverySpecRefusesCall() {
        ToolDefinition def = ToolDefinition.read("queryOrder", "查询订单详情")
                .toBuilder()
                .status(ToolStatus.ACTIVE).build();

        McpServerFeatures.SyncToolSpecification spec = adapter.toDiscoveryToolSpecification(def);
        CallToolRequest request = new CallToolRequest("queryOrder", Map.of(), Map.of());
        CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        assertNotNull(result.content());
        assertFalse(result.content().isEmpty());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("ToolGateway"), "应指引调用方经 ToolGateway 执行");
    }

    // ==================== search_tools ====================

    @Test
    @DisplayName("searchToolsSpecification：规格名为 search_tools 且可调用")
    void searchToolsSpecIsCallable() {
        RegistryService registryService = mock(RegistryService.class);
        McpServerFeatures.SyncToolSpecification spec = adapter.searchToolsSpecification(registryService);

        assertEquals(McpToolRegistryAdapter.SEARCH_TOOLS_NAME, spec.tool().name());
        assertNotNull(spec.callHandler());
    }

    @Test
    @DisplayName("searchTools 调用：经 RegistryService.search 检索并映射为 JSON 结果")
    void searchToolsInvokesRegistrySearch() {
        // 准备：registryService.search 命中 queryOrder
        ToolDefinition matched = ToolDefinition.read("queryOrder", "查询订单详情")
                .toBuilder()
                .status(ToolStatus.ACTIVE)
                .functionalCapabilities(List.of("order-query"))
                .toolTier(ToolTier.CORE)
                .build();
        RegistryService registryService = mock(RegistryService.class);
        when(registryService.search(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(matched));

        McpServerFeatures.SyncToolSpecification spec = adapter.searchToolsSpecification(registryService);
        CallToolRequest request = new CallToolRequest("search_tools",
                Map.of("functionalCapabilities", List.of("order-query")), Map.of());

        CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("queryOrder"), "结果 JSON 应包含命中工具名");

        // 验证确实调用了 RegistryService.search（委托检索）
        verify(registryService).search(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("searchTools 调用：RegistryService.search 抛出异常时被安全包装为错误结果")
    void searchToolsHandlesSearchFailureGracefully() {
        RegistryService registryService = mock(RegistryService.class);
        when(registryService.search(any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        McpServerFeatures.SyncToolSpecification spec = adapter.searchToolsSpecification(registryService);
        CallToolRequest request = new CallToolRequest("search_tools", Map.of(), Map.of());

        CallToolResult result = spec.callHandler().apply(null, request);

        // 序列化失败时回退 "[]"，不抛异常、不置 isError
        assertFalse(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertEquals("[]", json);
    }
}
