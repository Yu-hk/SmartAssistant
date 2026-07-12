package com.example.smartassistant.common.tool.provider;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.example.smartassistant.common.gateway.tool.mcp.McpBackendToolExecutor;
import com.example.smartassistant.common.gateway.tool.mcp.McpToolCallbackFactory;
import com.example.smartassistant.common.tool.client.ToolRegistryClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link SpringToolProvider} MCP-backed 分支单元测试（T2c-5 预留桩 + T2d 验证）。
 *
 * <p>T2c 的 MCP-backed merge 分支（McpToolCallbackFactory 注入 SpringToolProvider）需在
 * T2c 实现完成后启用。当前测试只验证已落地的 CORE 常驻 + allowlist 路径。</p>
 */
class SpringToolProviderMcpBackedTest {

    /** 含 @Tool 方法的本地 Bean。 */
    static class LocalToolBean {
        @Tool(description = "本地操作")
        public String doOp(String x) {
            return "local:" + x;
        }
    }

    private static ToolGateway stubGateway() {
        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.execute(any(ToolDefinition.class), any(ToolGateway.ToolExecutor.class), any(), any()))
                .thenAnswer(inv -> ((ToolGateway.ToolExecutor) inv.getArgument(1)).execute());
        return gateway;
    }

    /** 子类：override findToolBeans 直接注入本地 Bean 列表。 */
    static class TestableProvider extends SpringToolProvider {
        private final List<Object> beans;
        TestableProvider(ApplicationContext ac, ToolRegistryClient rc, ToolGateway gw, ToolRegistry reg,
                         List<Object> beans) {
            super(ac, rc, gw, reg);
            this.beans = beans;
        }
        @Override
        List<Object> findToolBeans() {
            return beans;
        }
    }

    @Test
    void centerPathUnchangedWhenOnlyLocalTool() {
        ApplicationContext appContext = mock(ApplicationContext.class);
        when(appContext.getBeanDefinitionNames()).thenReturn(new String[0]);

        ToolRegistryClient registryClient = mock(ToolRegistryClient.class);
        when(registryClient.getToolDefinitions("ORDER")).thenReturn(List.of());

        ToolGateway gateway = stubGateway();

        SpringToolProvider provider = new TestableProvider(appContext, registryClient, gateway,
                new ToolRegistry(), List.of(new LocalToolBean()));

        List<ToolCallback> cbs = provider.getToolCallbacks("ORDER");

        // CORE 工具常驻，仅走本地路径
        assertEquals(1, cbs.size());
        assertEquals("local:hi", cbs.get(0).call("hi"));
    }

    @Test
    void isMcpBackedHelperDetectsBackendTools() {
        ToolDefinition mcpDef = ToolDefinition.builder()
                .name("consumer.executeQuery")
                .description("后端 MCP 工具")
                .endpoint("http://backend:8081/mcp")
                .inputSchema("{\"type\":\"object\"}")
                .toolTier(ToolTier.SHARED)
                .riskLevel(ToolRiskLevel.READ)
                .build();
        assertTrue(McpToolCallbackFactory.isMcpBacked(mcpDef));
        assertFalse(McpToolCallbackFactory.isMcpBacked(
                ToolDefinition.builder().name("center.op").build()));
    }
}
