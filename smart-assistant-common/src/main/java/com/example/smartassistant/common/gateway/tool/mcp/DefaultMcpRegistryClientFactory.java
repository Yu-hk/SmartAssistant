package com.example.smartassistant.common.gateway.tool.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;

import java.time.Duration;

/**
 * 生产实现：使用 JDK {@code HttpClient} 的 {@link HttpClientSseClientTransport}（SSE，非 WebFlux）
 * 手动构造 {@link McpSyncClient} 连接 registry MCP server。
 *
 * <p>SDK 自动在 {@code endpoint} 后追加 {@code /sse}（与 registry
 * {@code spring.ai.mcp.server.sse-endpoint: /sse} 对齐）。不使用 Spring AI client 自动装配，
 * 故消费方无需 {@code spring.ai.mcp.client.enabled=false} 守门（与 T2b 拉取后端一致）。</p>
 */
public class DefaultMcpRegistryClientFactory implements McpRegistryClientFactory {

    private final String endpoint;
    private final int requestTimeoutMs;

    public DefaultMcpRegistryClientFactory(String endpoint, int requestTimeoutMs) {
        this.endpoint = endpoint;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    @Override
    public McpSyncClient createClient() throws Exception {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(endpoint).build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(requestTimeoutMs))
                .build();
    }
}
