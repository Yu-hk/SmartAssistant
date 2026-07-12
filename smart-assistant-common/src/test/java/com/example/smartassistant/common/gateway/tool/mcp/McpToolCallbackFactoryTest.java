package com.example.smartassistant.common.gateway.tool.mcp;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpToolCallbackFactory} 单元测试（T2c-3）。
 *
 * <ul>
 *   <li>{@code isMcpBacked}：{@code endpoint + inputSchema} 非空 → true；中心工具（二者为 null）→ false。</li>
 *   <li>{@code create} MCP-backed 分支：返回经 {@link ToolGateway} 包裹（def 感知重载）的回调，
 *       {@code call} 触发 {@link McpBackendToolExecutor#execute}（P0 治理 100% 经 ToolGateway）。</li>
 *   <li>{@code create} center 分支：传入本地 {@link ToolCallback}，返回包裹后回调；{@code call} 走本地 delegate。</li>
 *   <li><b>P0 治理包裹验证</b>：mock {@link ToolGateway}，断言 MCP-backed 回调 {@code call} 时
 *       {@link ToolGateway#execute(ToolDefinition, ToolGateway.ToolExecutor, String, String)}
 *       被调用（收到真实 {@code ToolDefinition} 而非字符串重载）。</li>
 * </ul>
 */
class McpToolCallbackFactoryTest {

    private ToolRegistry newRegistry() {
        return new ToolRegistry();
    }

    private ToolDefinition mcpBackedDef() {
        return ToolDefinition.builder()
                .name("consumer.executeQuery")
                .description("查询消费者")
                .endpoint("http://backend:8081/mcp")
                .inputSchema("{\"type\":\"object\"}")
                .toolTier(ToolTier.SHARED)
                .riskLevel(ToolRiskLevel.READ)
                .build();
    }

    private ToolDefinition centerDef() {
        return ToolDefinition.builder()
                .name("order.localOp")
                .description("本地操作")
                .toolTier(ToolTier.CORE)
                .riskLevel(ToolRiskLevel.READ)
                .build();
    }

    // ==================== isMcpBacked 判定 ====================

    @Test
    void isMcpBackedTrueWhenEndpointAndInputSchemaPresent() {
        assertTrue(McpToolCallbackFactory.isMcpBacked(mcpBackedDef()));
    }

    @Test
    void isMcpBackedFalseForCenterToolWithNullEndpoint() {
        assertFalse(McpToolCallbackFactory.isMcpBacked(centerDef()));
    }

    @Test
    void isMcpBackedFalseWhenEndpointNull() {
        ToolDefinition def = ToolDefinition.builder()
                .name("x.y")
                .description("d")
                .inputSchema("{}")
                .build();
        assertFalse(McpToolCallbackFactory.isMcpBacked(def));
    }

    @Test
    void isMcpBackedFalseWhenDefNull() {
        assertFalse(McpToolCallbackFactory.isMcpBacked(null));
    }

    // ==================== create：MCP-backed 分支 ====================

    @Test
    void createMcpBackedShouldRouteThroughToolGatewayWithRealDef() throws Exception {
        McpBackendToolExecutor backendExecutor = mock(McpBackendToolExecutor.class);
        when(backendExecutor.execute(any(ToolDefinition.class), anyString())).thenReturn("backend-result");

        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.execute(any(ToolDefinition.class), any(ToolGateway.ToolExecutor.class), any(), any()))
                .thenAnswer(inv -> ((ToolGateway.ToolExecutor) inv.getArgument(1)).execute());

        McpToolCallbackFactory factory =
                new McpToolCallbackFactory(newRegistry(), gateway, backendExecutor);

        ToolDefinition def = mcpBackedDef();
        ToolCallback cb = factory.create(def, null);

        String result = cb.call("{\"q\":\"x\"}");
        assertEquals("backend-result", result);

        // 验证 100% 经 ToolGateway（def 感知重载）：以真实 ToolDefinition 收到
        verify(gateway).execute(eq(def), any(ToolGateway.ToolExecutor.class), any(), any());
        // 验证 backend executor 被调用（转发到后端 MCP server）
        verify(backendExecutor).execute(eq(def), eq("{\"q\":\"x\"}"));
    }

    @Test
    void createMcpBackedShouldUseDefAwareOverloadNotStringOverload() throws Exception {
        McpBackendToolExecutor backendExecutor = mock(McpBackendToolExecutor.class);
        when(backendExecutor.execute(any(ToolDefinition.class), anyString())).thenReturn("ok");

        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.execute(any(ToolDefinition.class), any(ToolGateway.ToolExecutor.class), any(), any()))
                .thenAnswer(inv -> ((ToolGateway.ToolExecutor) inv.getArgument(1)).execute());

        McpToolCallbackFactory factory =
                new McpToolCallbackFactory(newRegistry(), gateway, backendExecutor);

        ToolCallback cb = factory.create(mcpBackedDef(), null);
        cb.call("{}");

        // 关键 P0 断言：调用的是 execute(ToolDefinition, ...) 重载，而非 execute(String, ...)
        verify(gateway, atLeastOnce())
                .execute(any(ToolDefinition.class), any(ToolGateway.ToolExecutor.class), any(), any());
    }

    // ==================== create：center 分支 ====================

    @Test
    void createCenterShouldWrapLocalCallback() throws Exception {
        ToolCallback local = mock(ToolCallback.class);
        when(local.call(anyString())).thenReturn("local-result");
        when(local.getToolDefinition()).thenReturn(
                DefaultToolDefinition.builder().name("order.localOp").description("本地操作")
                        .inputSchema("{}").build());

        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.execute(any(ToolDefinition.class), any(ToolGateway.ToolExecutor.class), any(), any()))
                .thenAnswer(inv -> ((ToolGateway.ToolExecutor) inv.getArgument(1)).execute());

        McpToolCallbackFactory factory =
                new McpToolCallbackFactory(newRegistry(), gateway, mock(McpBackendToolExecutor.class));

        ToolCallback cb = factory.create(centerDef(), local);
        String result = cb.call("{}");
        assertEquals("local-result", result);
        verify(local).call("{}");
        verify(gateway).execute(any(ToolDefinition.class), any(ToolGateway.ToolExecutor.class), any(), any());
    }

    @Test
    void createCenterWithoutLocalCallbackShouldThrow() {
        ToolGateway gateway = mock(ToolGateway.class);
        McpToolCallbackFactory factory =
                new McpToolCallbackFactory(newRegistry(), gateway, mock(McpBackendToolExecutor.class));
        // center 分支必须提供 localCallback；未提供应抛 UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class, () -> factory.create(centerDef(), null));
    }

    // ==================== McpBackendToolCallback 内部类 ====================

    @Test
    void mcpBackendToolCallbackGetToolDefinitionReturnsDefName() {
        McpToolCallbackFactory factory =
                new McpToolCallbackFactory(newRegistry(), mock(ToolGateway.class), mock(McpBackendToolExecutor.class));
        ToolCallback cb = factory.create(mcpBackedDef(), null);
        assertNotNull(cb.getToolDefinition());
        assertEquals("consumer.executeQuery", cb.getToolDefinition().name());
    }
}
