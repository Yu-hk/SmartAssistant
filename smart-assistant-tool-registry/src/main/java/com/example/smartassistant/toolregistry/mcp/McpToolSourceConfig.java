package com.example.smartassistant.toolregistry.mcp;

import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * T2b MCP-backed 工具源配置（源接入）。
 * <p>
 * 绑定 {@code tool-registry.mcp-sources} 列表，每项描述一个后端 MCP server（如 consumer / order /
 * travel 的 {@code executeQuery} / {@code getTableSchema}）。{@link McpToolSourceIngestor} 据此
 * 反向映射并注册进中心目录，修复"数据分裂 #4"。
 * </p>
 *
 * <p>默认所有源 {@code enabled=false}，registry 启动不依赖任何后端 MCP server 运行；
 * 仅当运维显式开启某源时才发起 SSE 拉取。</p>
 *
 * @see McpToolSourceIngestor
 */
@Configuration
@ConfigurationProperties(prefix = "tool-registry")
@Getter
@Setter
public class McpToolSourceConfig {

    /** MCP-backed 后端工具源列表（T2b 源接入）。 */
    private List<McpToolSource> mcpSources = new ArrayList<>();

    /**
     * 单个后端 MCP 工具源配置。
     */
    @Getter
    @Setter
    public static class McpToolSource {
        /** 源唯一 ID（用于 tag 注入 {@code mcp-source:<sourceId>} 与日志追踪）。 */
        private String sourceId;

        /** 是否启用：关闭时 {@code syncSource} 直接跳过，registry 启动不依赖该后端。 */
        private boolean enabled = false;

        /** 传输类型，当前仅支持 sse。 */
        private String transport = "sse";

        /** 后端 MCP server 的 SSE 基址（scheme://host:port 或基路径），如 http://consumer:8081/mcp。 */
        private String endpoint;

        /** 命名空间前缀：工具名 = {@code <namespace>.<backendToolName>}（如 "consumer.executeQuery"）。 */
        private String namespace;

        /** 同步工具的默认分层（默认 SHARED：跨 agent 共享基建）。 */
        private ToolTier defaultTier = ToolTier.SHARED;

        /** 同步工具的默认风险等级（决定 capabilities 推导，默认 READ）。 */
        private ToolRiskLevel defaultRisk = ToolRiskLevel.READ;

        /** 种子功能性能力（业务动作语义），如 ["sql-query"]。 */
        private List<String> seedFunctionalCapabilities = new ArrayList<>();

        /** 基础标签，如 ["consumer","mcp-source:sql"]。 */
        private List<String> tags = new ArrayList<>();

        /** 同步控制（模式 / cron / 超时）。 */
        private Sync sync = new Sync();
    }

    /**
     * 同步调度配置（嵌套）。
     */
    @Getter
    @Setter
    public static class Sync {
        /** 同步模式：periodic（周期）| on-startup（仅启动）。 */
        private String mode = "periodic";

        /** Spring {@code CronTrigger} 表达式（periodic 模式下使用）。 */
        private String cron = "0 */5 * * * ?";

        /** 单次同步超时（毫秒，仅作客户端请求超时参考）。 */
        private int timeoutMs = 5000;
    }

    /**
     * 周期同步调度线程池（T2b 专用，避免与 Spring 默认单线程调度器相互阻塞）。
     * <p>守护线程，应用关闭时随上下文销毁。</p>
     */
    @Bean
    public ThreadPoolTaskScheduler mcpToolSourceScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("mcp-source-sync-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * 生产环境：真实 SSE MCP 客户端工厂。
     * <p>对给定源创建 {@link HttpClientSseClientTransport}（JDK 内置 HttpClient，非 WebFlux）
     * 连接后端，调 {@code listTools()} 返回后端工具列表。</p>
     *
     * <p>端点语义：{@code source.endpoint} 作为 SSE 基址（scheme://host:port 或基路径），
     * SDK 在其后追加默认 SSE 子路径；请与后端 {@code spring.ai.mcp.server.sse-endpoint} 对齐。
     * 单次请求超时取自 {@code sync.timeoutMs}（默认 5000ms）。</p>
     */
    @Bean
    public McpToolSourceIngestor.McpBackendClientFactory mcpBackendClientFactory() {
        return source -> {
            HttpClientSseClientTransport transport =
                    HttpClientSseClientTransport.builder(source.getEndpoint()).build();
            int timeoutMs = (source.getSync() != null && source.getSync().getTimeoutMs() > 0)
                    ? source.getSync().getTimeoutMs() : 5000;
            try (McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofMillis(timeoutMs)).build()) {
                return client.listTools().tools();
            }
        };
    }
}
