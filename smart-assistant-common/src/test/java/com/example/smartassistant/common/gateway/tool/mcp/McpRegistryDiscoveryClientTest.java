package com.example.smartassistant.common.gateway.tool.mcp;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.example.smartassistant.common.tool.client.ToolRegistryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link McpRegistryDiscoveryClient} 单元测试（不依赖真实 registry）。
 * 通过可注入的 {@link McpRegistryClientFactory} stub 返回 mock {@link McpSyncClient}。
 */
class McpRegistryDiscoveryClientTest {

    private McpRegistryClientFactory factory;
    private ObjectMapper objectMapper;
    private ToolRegistryProperties properties;
    private McpRegistryDiscoveryClient client;

    @BeforeEach
    void setUp() {
        factory = mock(McpRegistryClientFactory.class);
        objectMapper = new ObjectMapper();
        properties = new ToolRegistryProperties();
        client = new McpRegistryDiscoveryClient(factory, objectMapper, properties);
    }

    @Test
    void toToolDefinitionShouldMapMetaFields() {
        Tool tool = Tool.builder()
                .name("consumer.executeQuery")
                .description("查询消费者")
                .inputSchema(Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))))
                .annotations(ToolAnnotations.builder().readOnlyHint(false).build())
                .meta(Map.of(
                        "x-functional-capabilities", List.of("sql-query"),
                        "x-tool-tier", "SHARED",
                        "x-tags", List.of("consumer"),
                        "x-risk-level", "READ",
                        "functionalCapabilities", List.of("sql-query"),
                        "toolTier", "SHARED"))
                .build();

        ToolDefinition def = client.toToolDefinition(tool);

        assertEquals("consumer.executeQuery", def.getName());
        assertEquals("查询消费者", def.getDescription());
        assertEquals(List.of("sql-query"), def.getFunctionalCapabilities());
        assertEquals(ToolTier.SHARED, def.getToolTier());
        assertArrayEquals(new String[]{"consumer"}, def.getTags());
        assertEquals(ToolRiskLevel.READ, def.getRiskLevel());
        assertNotNull(def.getInputSchema());
    }

    @Test
    void readOnlyHintShouldDeriveRiskWhenNoMetaRisk() {
        Tool tool = Tool.builder()
                .name("foo.bar")
                .description("d")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                .meta(Map.of("x-tool-tier", "SHARED"))
                .build();

        ToolDefinition def = client.toToolDefinition(tool);

        assertEquals(ToolRiskLevel.READ, def.getRiskLevel());
    }

    @Test
    void listToolsShouldFilterByTags() throws Exception {
        Tool t1 = Tool.builder().name("a.x").description("d")
                .meta(Map.of("x-tags", List.of("A"), "x-tool-tier", "SHARED")).build();
        Tool t2 = Tool.builder().name("b.y").description("d")
                .meta(Map.of("x-tags", List.of("B"), "x-tool-tier", "SHARED")).build();
        McpSyncClient mockClient = mock(McpSyncClient.class);
        when(mockClient.listTools()).thenReturn(ListToolsResult.builder(List.of(t1, t2)).build());
        when(factory.createClient()).thenReturn(mockClient);

        List<ToolDefinition> defs = client.listTools(Set.of("A"));

        assertEquals(1, defs.size());
        assertEquals("a.x", defs.get(0).getName());
    }

    @Test
    void searchToolsShouldParseJsonArray() throws Exception {
        Map<String, Object> toolJson = Map.of(
                "name", "consumer.executeQuery",
                "description", "查询",
                "inputSchema", Map.of("type", "object"),
                "annotations", Map.of("readOnlyHint", true),
                "meta", Map.of(
                        "x-functional-capabilities", List.of("sql-query"),
                        "x-tool-tier", "SHARED",
                        "x-tags", List.of("consumer"),
                        "x-risk-level", "READ"));
        String json = objectMapper.writeValueAsString(List.of(toolJson));
        CallToolResult result = CallToolResult.builder().addTextContent(json).build();
        McpSyncClient mockClient = mock(McpSyncClient.class);
        when(mockClient.callTool(any())).thenReturn(result);
        when(factory.createClient()).thenReturn(mockClient);

        List<ToolDefinition> defs = client.searchTools(
                new McpRegistryDiscoveryClient.McpSearchFilter().functionalCapabilities(List.of("sql-query")));

        assertEquals(1, defs.size());
        assertEquals("consumer.executeQuery", defs.get(0).getName());
        assertEquals(List.of("sql-query"), defs.get(0).getFunctionalCapabilities());
    }

    @Test
    void listToolsShouldPropagateException() throws Exception {
        when(factory.createClient()).thenThrow(new RuntimeException("conn refused"));
        assertThrows(Exception.class, () -> client.listTools(Set.of()));
    }
}
