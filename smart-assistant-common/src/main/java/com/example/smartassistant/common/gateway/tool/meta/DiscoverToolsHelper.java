package com.example.smartassistant.common.gateway.tool.meta;

import com.example.smartassistant.common.agent.SmartReActAgent;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * T2d 工具发现辅助工具，消除三个 AgentConfig 中的重复逻辑。
 *
 * <p>提供两个操作：</p>
 * <ol>
 *   <li>{@link #injectDiscoverTools(List, DiscoverToolsTool)} — 将 {@code discover_tools} 元工具
 *       注入到 Agent 的工具列表中，使 LLM 在推理时能调用它发现并动态加载新工具。</li>
 *   <li>{@link #bindRegistrar(DiscoverToolsTool, SmartReActAgent)} — 将注册器绑定到 Agent，
 *       使 {@code DiscoverToolsTool} 发现工具后能实际注入到 Agent 的运行时工具集。</li>
 * </ol>
 *
 * <p>两操作缺一不可：注入让 LLM ＂能调＂discover_tools，绑定让发现结果 ＂能生效＂。</p>
 */
public final class DiscoverToolsHelper {

    private DiscoverToolsHelper() {
    }

    /**
     * 将 {@code discover_tools} 元工具注入到 Agent 的工具列表中。
     *
     * @param moduleTools       本模块预加载的工具列表（可后续追加发现工具）
     * @param discoverToolsTool T2d 发现元工具 Bean；为 {@code null} 时返回原列表
     * @return 注入后的工具列表（新列表，原列表不受影响）
     */
    public static List<ToolCallback> injectDiscoverTools(
            List<ToolCallback> moduleTools, DiscoverToolsTool discoverToolsTool) {
        if (discoverToolsTool == null) {
            return moduleTools;
        }

        ToolCallback[] discoverCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(discoverToolsTool)
                .build()
                .getToolCallbacks();

        List<ToolCallback> result = new ArrayList<>(moduleTools);
        for (ToolCallback cb : discoverCallbacks) {
            result.add(cb);
        }
        return result;
    }

    /**
     * 将 {@code toolRegistrar} 绑定到 Agent，使 {@code discover_tools}
     * 发现的工具通过 {@code agent.registerDiscoveredTool()} 动态注入。
     *
     * @param discoverToolsTool T2d 发现元工具 Bean；为 {@code null} 时无操作
     * @param agent             目标 Agent 实例
     */
    public static void bindRegistrar(DiscoverToolsTool discoverToolsTool, SmartReActAgent agent) {
        if (discoverToolsTool == null) {
            return;
        }
        discoverToolsTool.setToolRegistrar(callbacks ->
                agent.registerDiscoveredTool(callbacks.toArray(new ToolCallback[0])));
    }
}
