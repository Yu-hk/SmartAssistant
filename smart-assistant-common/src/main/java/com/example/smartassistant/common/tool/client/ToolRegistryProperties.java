package com.example.smartassistant.common.tool.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tool Registry 客户端配置属性。
 * <p>
 * 从 {@code tool-registry.*} 前缀读取配置。
 * </p>
 *
 * <pre>{@code
 * tool-registry:
 *   url: http://localhost:8088         # Registry 服务地址
 *   cache-ttl-seconds: 30              # 本地缓存 TTL（秒）
 *   connect-timeout-ms: 2000           # 连接超时（毫秒）
 *   read-timeout-ms: 5000              # 读取超时（毫秒）
 *   # ===== T2c：MCP 发现 / 转发（默认关闭，一键回退 T2' 前行为）=====
 *   mcp-discovery-enabled: false       # 是否启用 MCP 优先发现通道（true=经 registry MCP server 发现）
 *   mcp-endpoint: http://localhost:8088# registry MCP server SSE 基址（默认 = url，SDK 自动补 /sse）
 *   mcp-request-timeout-ms: 5000       # 发现（listTools/search_tools）请求超时（毫秒）
 *   mcp-backend-request-timeout-ms: 5000 # 后端 MCP server tools/call 转发超时（毫秒）
 *   mcp-backend-max-idle-seconds: 300  # 后端连接池最大空闲时间（预留，当前按需懒加载+重连）
 * }</pre>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
@ConfigurationProperties(prefix = "tool-registry")
public class ToolRegistryProperties {

    /** Registry 服务地址 */
    private String url = "http://localhost:8088";

    /** 本地缓存 TTL（秒） */
    private int cacheTtlSeconds = 30;

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 2000;

    /** 读取超时（毫秒） */
    private int readTimeoutMs = 5000;

    // ==================== T2c：MCP 发现 / 转发配置 ====================

    /** 是否启用 MCP 优先发现通道；false 时 ToolRegistryClient 完全走原 REST 路径（零影响、可回退）。 */
    private boolean mcpDiscoveryEnabled = false;

    /** registry MCP server SSE 基址；默认 = {@link #url}，SDK 自动追加 /sse。 */
    private String mcpEndpoint;

    /** 发现（listTools / search_tools）请求超时（毫秒）。 */
    private int mcpRequestTimeoutMs = 5000;

    /** 后端 MCP server tools/call 转发超时（毫秒）。 */
    private int mcpBackendRequestTimeoutMs = 5000;

    /** 后端连接池最大空闲时间（秒）；预留，当前按需懒加载 + 失败重连。 */
    private int mcpBackendMaxIdleSeconds = 300;

    // ==================== T2d：Agent 侧发现 / 护栏配置 ====================

    /** ⭐ T2d 特性开关：是否启用 Agent 侧 MCP 发现（discover_tools 元工具注入）；默认 false，关时行为 = T2c 前（REST 路径）。 */
    private boolean t2McpDiscoveryEnabled = false;

    /** 每轮最大发现次数 (T2d 护栏) */
    private int maxDiscoveriesPerTurn = 1;

    /** 每会话最大发现次数 (T2d 护栏) */
    private int maxDiscoveriesPerSession = 10;

    /** 最大动态工具数 (T2d 护栏) */
    private int maxDynamicTools = 15;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    // ==================== T2c：MCP 发现 / 转发 getters & setters ====================

    public boolean isMcpDiscoveryEnabled() {
        return mcpDiscoveryEnabled;
    }

    public void setMcpDiscoveryEnabled(boolean mcpDiscoveryEnabled) {
        this.mcpDiscoveryEnabled = mcpDiscoveryEnabled;
    }

    /** registry MCP server SSE 基址；未显式配置时回退到 {@link #url}。 */
    public String getMcpEndpoint() {
        return mcpEndpoint != null ? mcpEndpoint : url;
    }

    public void setMcpEndpoint(String mcpEndpoint) {
        this.mcpEndpoint = mcpEndpoint;
    }

    public int getMcpRequestTimeoutMs() {
        return mcpRequestTimeoutMs;
    }

    public void setMcpRequestTimeoutMs(int mcpRequestTimeoutMs) {
        this.mcpRequestTimeoutMs = mcpRequestTimeoutMs;
    }

    public int getMcpBackendRequestTimeoutMs() {
        return mcpBackendRequestTimeoutMs;
    }

    public void setMcpBackendRequestTimeoutMs(int mcpBackendRequestTimeoutMs) {
        this.mcpBackendRequestTimeoutMs = mcpBackendRequestTimeoutMs;
    }

    public int getMcpBackendMaxIdleSeconds() {
        return mcpBackendMaxIdleSeconds;
    }

    public void setMcpBackendMaxIdleSeconds(int mcpBackendMaxIdleSeconds) {
        this.mcpBackendMaxIdleSeconds = mcpBackendMaxIdleSeconds;
    }

    // ==================== T2d：Agent 侧发现 / 护栏 getters & setters ====================

    public boolean isT2McpDiscoveryEnabled() {
        return t2McpDiscoveryEnabled;
    }

    public void setT2McpDiscoveryEnabled(boolean t2McpDiscoveryEnabled) {
        this.t2McpDiscoveryEnabled = t2McpDiscoveryEnabled;
    }

    public int getMaxDiscoveriesPerTurn() {
        return maxDiscoveriesPerTurn;
    }

    public void setMaxDiscoveriesPerTurn(int maxDiscoveriesPerTurn) {
        this.maxDiscoveriesPerTurn = maxDiscoveriesPerTurn;
    }

    public int getMaxDiscoveriesPerSession() {
        return maxDiscoveriesPerSession;
    }

    public void setMaxDiscoveriesPerSession(int maxDiscoveriesPerSession) {
        this.maxDiscoveriesPerSession = maxDiscoveriesPerSession;
    }

    public int getMaxDynamicTools() {
        return maxDynamicTools;
    }

    public void setMaxDynamicTools(int maxDynamicTools) {
        this.maxDynamicTools = maxDynamicTools;
    }
}
