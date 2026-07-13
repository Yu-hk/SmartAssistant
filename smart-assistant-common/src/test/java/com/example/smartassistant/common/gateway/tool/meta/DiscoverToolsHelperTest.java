package com.example.smartassistant.common.gateway.tool.meta;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.gateway.tool.mcp.McpRegistryDiscoveryClient;
import com.example.smartassistant.common.gateway.tool.mcp.McpToolCallbackFactory;
import com.example.smartassistant.common.tool.client.ToolRegistryProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link DiscoverToolsHelper} 单元测试：覆盖 discover_tools 注入与注册器绑定。
 */
@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class DiscoverToolsHelperTest {

    @org.mockito.Mock
    private McpRegistryDiscoveryClient discoveryClient;

    @org.mockito.Mock
    private McpToolCallbackFactory callbackFactory;

    private DiscoverToolsTool newTool() {
        return new DiscoverToolsTool(discoveryClient, callbackFactory,
                new ToolRegistryProperties(), new com.fasterxml.jackson.databind.ObjectMapper(), ObservationRegistry.NOOP);
    }

    @Test
    @DisplayName("injectDiscoverTools: discoverToolsTool 为 null → 返回原列表（同引用）")
    void inject_nullTool_returnsOriginal() {
        List<ToolCallback> base = List.of(mock(ToolCallback.class));
        List<ToolCallback> result = DiscoverToolsHelper.injectDiscoverTools(base, null);
        assertSame(base, result);
    }

    @Test
    @DisplayName("injectDiscoverTools: 注入真实元工具 → 列表追加 discover_tools 回调")
    void inject_withTool_addsDiscoverCallback() {
        List<ToolCallback> base = new java.util.ArrayList<>(List.of(mock(ToolCallback.class)));
        List<ToolCallback> result = DiscoverToolsHelper.injectDiscoverTools(base, newTool());
        assertEquals(base.size() + 1, result.size());
        boolean hasDiscover = result.stream()
                .anyMatch(cb -> {
                    ToolDefinition def = cb.getToolDefinition();
                    return def != null && "discover_tools".equals(def.name());
                });
        assertTrue(hasDiscover, "应包含名为 discover_tools 的回调");
    }

    @Test
    @DisplayName("bindRegistrar: discoverToolsTool 为 null → 无操作不抛异常")
    void bind_null_noException() {
        assertDoesNotThrow(() ->
                DiscoverToolsHelper.bindRegistrar(null, mock(SmartReActAgent.class)));
    }

    @Test
    @DisplayName("bindRegistrar: 绑定后注册器触发 → 工具实际注入 Agent 的 dynamicTools")
    void bind_withTool_wiresRegistrarToAgent() throws Exception {
        SmartReActAgent agent = new SmartReActAgent(mock(ChatModel.class));
        DiscoverToolsTool tool = newTool();
        DiscoverToolsHelper.bindRegistrar(tool, agent);

        // 通过反射取出 helper 设置的注册器并触发它
        Field regField = DiscoverToolsTool.class.getDeclaredField("toolRegistrar");
        regField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Consumer<List<ToolCallback>> registrar = (Consumer<List<ToolCallback>>) regField.get(tool);
        assertNotNull(registrar, "bindRegistrar 应设置 toolRegistrar");

        ToolCallback fake = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn("fakeX");
        when(fake.getToolDefinition()).thenReturn(def);
        registrar.accept(List.of(fake));

        // 验证 Agent 的 dynamicTools 确实增加了
        Field dtField = SmartReActAgent.class.getDeclaredField("dynamicTools");
        dtField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ToolCallback> dynamicTools = (List<ToolCallback>) dtField.get(agent);
        assertEquals(1, dynamicTools.size());
        assertEquals("fakeX", dynamicTools.get(0).getToolDefinition().name());
    }
}
