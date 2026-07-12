package com.example.smartassistant.common.gateway.tool.mcp;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * 创建连接 registry MCP server 的 {@link McpSyncClient} 工厂。
 *
 * <p>生产实现使用 JDK {@code HttpClient} 的 SSE transport（非 WebFlux）手动构造 client；
 * 测试可注入返回固定工具列表 stub 的工厂，从而不依赖真实 registry（镜像 T2b 的
 * {@code McpBackendClientFactory} 模式，保证「可测性」）。</p>
 *
 * @see DefaultMcpRegistryClientFactory
 */
public interface McpRegistryClientFactory {

    /**
     * 创建一个已初始化的 registry MCP client。
     *
     * @return 连接 registry MCP server 的同步 client（调用方负责关闭）
     * @throws Exception 任意连接 / 初始化异常（本接口不吞，交由上层 {@code ToolRegistryClient} 降级）
     */
    McpSyncClient createClient() throws Exception;
}
