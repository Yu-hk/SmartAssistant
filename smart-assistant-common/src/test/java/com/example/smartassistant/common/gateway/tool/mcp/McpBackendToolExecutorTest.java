package com.example.smartassistant.common.gateway.tool.mcp;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.tool.client.ToolRegistryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpBackendToolExecutor} 单元测试（不依赖真实后端 MCP server）。
 * 通过 {@link TestableExecutor} 子类 override {@code getOrCreateClient/createClient} 注入 mock client。
 */
class McpBackendToolExecutorTest {

    @Test
    void stripNamespaceShouldRemoveFirstSegment() {
        assertEquals("executeQuery", McpBackendToolExecutor.stripNamespace("consumer.executeQuery"));
        assertEquals("foo", McpBackendToolExecutor.stripNamespace("foo"));
        assertNull(McpBackendToolExecutor.stripNamespace(null));
    }

    @Test
    void executeShouldCallBackendWithStrippedNameAndArgs() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        CallToolResult result = CallToolResult.builder().addTextContent("{\"rows\":1}").build();
        when(client.callTool(any())).thenReturn(result);

        TestableExecutor executor = new TestableExecutor(new ToolRegistryProperties(), new ObjectMapper(), client);
        ToolDefinition def = ToolDefinition.builder()
                .name("consumer.executeQuery")
                .description("查询")
                .endpoint("http://backend:8081/mcp")
                .inputSchema("{\"type\":\"object\"}")
                .build();

        String out = executor.execute(def, "{\"q\":\"x\"}");

        assertEquals("{\"rows\":1}", out);
        verify(client).callTool(argThat(req ->
                "executeQuery".equals(req.name()) && req.arguments() != null && req.arguments().containsKey("q")));
    }

    @Test
    void executeShouldReconnectOnFailure() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        CallToolResult ok = CallToolResult.builder().addTextContent("recovered").build();
        when(client.callTool(any()))
                .thenThrow(new RuntimeException("conn lost"))
                .thenReturn(ok);

        TestableExecutor executor = new TestableExecutor(new ToolRegistryProperties(), new ObjectMapper(), client);
        ToolDefinition def = ToolDefinition.builder()
                .name("consumer.executeQuery")
                .endpoint("http://backend:8081/mcp")
                .inputSchema("{}")
                .build();

        String out = executor.execute(def, "{}");

        assertEquals("recovered", out);
        // 初始连接 + 失败重连，共创建 2 次；不抛穿
        assertEquals(2, executor.createCount.get());
    }

    @Test
    void closeAllShouldCloseAllClients() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        CallToolResult ok = CallToolResult.builder().addTextContent("x").build();
        when(client.callTool(any())).thenReturn(ok);

        TestableExecutor executor = new TestableExecutor(new ToolRegistryProperties(), new ObjectMapper(), client);
        ToolDefinition def = ToolDefinition.builder()
                .name("consumer.executeQuery")
                .endpoint("http://backend:8081/mcp")
                .inputSchema("{}")
                .build();
        executor.execute(def, "{}"); // 填充连接池

        executor.closeAll();

        verify(client, times(1)).close();
        assertTrue(executor.poolIsEmpty());
    }

    /** 子类：override 连接创建方法注入固定 mock client，并计数创建次数。 */
    static class TestableExecutor extends McpBackendToolExecutor {
        private final McpSyncClient client;
        final AtomicInteger createCount = new AtomicInteger();
        /** 跟踪已连接的 endpoint，供 closeAll / poolSize 验证。 */
        private final Set<String> connectedEndpoints = ConcurrentHashMap.newKeySet();

        TestableExecutor(ToolRegistryProperties props, ObjectMapper om, McpSyncClient client) {
            super(props, om);
            this.client = client;
        }

        @Override
        McpSyncClient getOrCreateClient(String endpoint) {
            createCount.incrementAndGet();
            connectedEndpoints.add(endpoint);
            return client;
        }

        @Override
        McpSyncClient createClient(String endpoint) {
            createCount.incrementAndGet();
            connectedEndpoints.add(endpoint);
            return client;
        }

        @Override
        public void closeAll() {
            try {
                client.close();
            } catch (Exception ex) {
                // ignore
            }
            connectedEndpoints.clear();
        }

        @Override
        int poolSize() {
            return connectedEndpoints.size();
        }

        boolean poolIsEmpty() {
            return poolSize() == 0;
        }
    }
}
