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
}
