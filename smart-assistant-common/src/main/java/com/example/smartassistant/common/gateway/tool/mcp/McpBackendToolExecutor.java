package com.example.smartassistant.common.gateway.tool.mcp;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.tool.client.ToolRegistryProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后端 MCP 执行转发器（T2c-2）。
 *
 * <p>经 {@link McpBackendToolExecutor#execute(ToolDefinition, String)} 把 MCP-backed 工具的
 * {@code tools/call} 转发到对应的「后端 MCP server」：
 * <ol>
 *   <li>{@link #stripNamespace(String)} 去首个 {@code .} 前缀得后端工具名（如 {@code consumer.executeQuery} → {@code executeQuery}）；</li>
 *   <li>{@link #getOrCreateClient(String)} 按 {@code endpoint} 懒加载 + 失败重连连接池；</li>
 *   <li>把 LLM 给的 {@code toolInput}（JSON）解析为 {@code Map} 原样透传（不校验，后端自行校验）；</li>
 *   <li>构造 {@code CallToolRequest} 调后端 {@code callTool}；</li>
 *   <li>提取 {@code TextContent.text()} 拼接返回。</li>
 * </ol>
 *
 * <p><b>治理衔接：</b>本类<b>不做</b>限流 / 超时 / 熔断 —— 全部由 {@link com.example.smartassistant.common.gateway.tool.ToolGateway}
 * 复用（调用方必须经 {@code ToolGatewayToolCallback} 包裹后才会进入本类）。本类仅做连接级超时（取自
 * {@code mcpBackendRequestTimeoutMs}）与失败重连。</p>
 *
 * <p><b>连接池生命周期：</b>{@code endpoint → McpSyncClient} 长连接，懒加载；{@link #closeAll()} 必须显式关闭
 * 所有 client（避免 SSE 连接泄漏），由 {@code @PreDestroy} 触发。</p>
 */
public class McpBackendToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpBackendToolExecutor.class);

    private final ToolRegistryProperties props;
    private final ObjectMapper objectMapper;
    private final Map<String, McpSyncClient> pool = new ConcurrentHashMap<>();

    public McpBackendToolExecutor(ToolRegistryProperties props, ObjectMapper objectMapper) {
        this.props = Objects.requireNonNull(props, "props");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 转发执行一个 MCP-backed 工具调用到后端 MCP server。
     *
     * @param def       中心目录完整定义（含 endpoint / inputSchema，由 ToolGateway 治理后传入）
     * @param toolInput LLM 传入的 JSON 参数串
     * @return 后端返回的文本拼接结果
     * @throws Exception 任意连接 / 调用异常（向上抛，由 ToolGateway 包裹为 ToolExecutionException）
     */
    public String execute(ToolDefinition def, String toolInput) throws Exception {
        Objects.requireNonNull(def, "def must not be null");
        String backendName = stripNamespace(def.getName());
        String endpoint = def.getEndpoint();
        Map<String, Object> args = parseToolInput(toolInput);
        CallToolRequest request = CallToolRequest.builder().name(backendName).arguments(args).build();
        McpSyncClient client = getOrCreateClient(endpoint);
        try {
            return extractText(client.callTool(request));
        } catch (Exception e) {
            log.warn("[McpBackendToolExecutor] 后端调用失败，尝试重连: endpoint={}, tool={}, error={}",
                    endpoint, backendName, e.getMessage());
            reconnect(endpoint);
            McpSyncClient reconnected = getOrCreateClient(endpoint);
            return extractText(reconnected.callTool(request));
        }
    }

    /**
     * 按 endpoint 取（或懒加载）后端 MCP client；失败重连发生在 {@link #execute} 捕获异常后。
     * 包级可见，便于单测 override 为 mock。
     */
    McpSyncClient getOrCreateClient(String endpoint) throws Exception {
        if (endpoint == null) {
            throw new IllegalArgumentException("MCP-backed 工具 endpoint 为空，无法转发到后端: " + "（工具名可能未含 endpoint）");
        }
        McpSyncClient client = pool.get(endpoint);
        if (client != null) {
            return client;
        }
        synchronized (pool) {
            client = pool.get(endpoint);
            if (client != null) {
                return client;
            }
            client = createClient(endpoint);
            pool.put(endpoint, client);
            return client;
        }
    }

    /** 创建连接某后端 endpoint 的 MCP client（包级可见，便于单测 override）。 */
    McpSyncClient createClient(String endpoint) throws Exception {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(endpoint).build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(props.getMcpBackendRequestTimeoutMs()))
                .build();
    }

    /** 关闭旧 client 并从连接池移除（重连前调用）。 */
    private void reconnect(String endpoint) {
        McpSyncClient old = pool.remove(endpoint);
        if (old != null) {
            try {
                old.close();
            } catch (Exception ex) {
                log.debug("[McpBackendToolExecutor] 关闭旧后端 MCP client 异常: {}", ex.getMessage());
            }
        }
    }

    private Map<String, Object> parseToolInput(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(toolInput,
                    new TypeReference<Map<String, Object>>() {});
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            log.warn("[McpBackendToolExecutor] toolInput 解析失败，以空参数转发: {}", e.getMessage());
            return Map.of();
        }
    }

    private String extractText(CallToolResult result) {
        if (result == null || result.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc) {
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    /**
     * 去首个 {@code .} 之前的前缀（{@code consumer.executeQuery} → {@code executeQuery}）；无 {@code .} 原样返回。
     */
    public static String stripNamespace(String fullName) {
        if (fullName == null) {
            return null;
        }
        int idx = fullName.indexOf('.');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

	/**
	 * 连接池大小（包级可见，供测试验证连接池管理）。
	 */
	int poolSize() {
		return pool.size();
	}

	/**
	 * 关闭连接池中所有后端 MCP client（{@code @PreDestroy} 生命周期，避免 SSE 连接泄漏）。
	 */
    @PreDestroy
    public void closeAll() {
        for (Map.Entry<String, McpSyncClient> entry : pool.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception ex) {
                log.debug("[McpBackendToolExecutor] 关闭后端 MCP client 异常: {}", ex.getMessage());
            }
        }
        pool.clear();
    }
}
