package com.example.smartassistant.common.tool.provider;

import org.springframework.ai.tool.ToolCallback;
import java.util.List;

/**
 * 工具发现接口。
 * <p>
 * 根据 Agent 标签自动发现匹配的工具 Bean，组装为 {@link ToolCallback} 列表。
 * Agent Config 不再注入具体工具 Bean，仅依赖此接口 + {@code ToolRegistryClient}。
 * </p>
 */
public interface ToolProvider {

    /**
     * 获取指定标签对应的 ToolCallback 列表。
     * <p>
     * 实现类应：
     * <ol>
     *   <li>从 ApplicationContext 扫描所有含 {@code @Tool} 方法的 Bean</li>
     *   <li>从 Registry 查询指定 tag 的已注册工具定义</li>
     *   <li>交差匹配，只返回在 Registry 中注册且 tag 匹配的工具</li>
     * </ol>
     *
     * @param tag 域标签，如 "ORDER" / "PRODUCT" / "GENERAL"
     * @return 匹配的 ToolCallback 列表（按工具名排序，保证确定性）
     */
    List<ToolCallback> getToolCallbacks(String tag);
}
