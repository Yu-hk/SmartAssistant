package com.example.smartassistant.common.gateway.tool.mcp;

import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.meta.DiscoverToolsTool;
import com.example.smartassistant.common.tool.client.ToolRegistryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * T2c MCP 发现 / 转发自动配置。
 *
 * <p>注册以下 bean（均 {@code @ConditionalOnMissingBean}，便于测试或上层覆盖）：
 * <ul>
 *   <li>{@link McpRegistryClientFactory} — 生产 = {@link DefaultMcpRegistryClientFactory}（SSE 连 registry）；</li>
 *   <li>{@link McpRegistryDiscoveryClient} — registry 发现客户端（懒连接，{@code @PreDestroy} 关闭）；</li>
 *   <li>{@link McpBackendToolExecutor} — 后端 MCP 转发执行器（连接池，{@code @PreDestroy} 关闭）；</li>
 *   <li>{@link McpToolCallbackFactory} — 回调工厂（统一 {@link ToolGateway} 治理包裹）。</li>
 * </ul>
 *
 * <p>特性开关 {@code tool-registry.mcp-discovery-enabled=false} 时这些 bean 仍会创建（创建不触发连接），
 * 但 {@code ToolRegistryClient} 完全走原 REST 路径，T2c 改动零影响、可一键回退。</p>
 */
@AutoConfiguration
public class McpDiscoveryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpDiscoveryAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public McpRegistryClientFactory mcpRegistryClientFactory(ToolRegistryProperties properties) {
        return new DefaultMcpRegistryClientFactory(properties.getMcpEndpoint(),
                properties.getMcpRequestTimeoutMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public McpRegistryDiscoveryClient mcpRegistryDiscoveryClient(McpRegistryClientFactory clientFactory,
                                                                ObjectMapper objectMapper,
                                                                ToolRegistryProperties properties) {
        log.info("[McpDiscoveryAutoConfiguration] 初始化 McpRegistryDiscoveryClient: mcpEndpoint={}, enabled={}",
                properties.getMcpEndpoint(), properties.isMcpDiscoveryEnabled());
        return new McpRegistryDiscoveryClient(clientFactory, objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public McpBackendToolExecutor mcpBackendToolExecutor(ToolRegistryProperties properties,
                                                         ObjectMapper objectMapper) {
        return new McpBackendToolExecutor(properties, objectMapper);
    }

	@Bean
	@ConditionalOnMissingBean
	public McpToolCallbackFactory mcpToolCallbackFactory(ToolRegistry toolRegistry,
	                                                     ToolGateway gateway,
	                                                     McpBackendToolExecutor backendExecutor) {
		return new McpToolCallbackFactory(toolRegistry, gateway, backendExecutor);
	}

	/**
	 * T2d：发现元工具（受 {@code tool-registry.t2-mcp-discovery-enabled} 特性开关控制）。
	 * <p>开关关闭时本 bean 不创建，Agent 侧无 {@code discover_tools} 能力。</p>
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "tool-registry", name = "t2-mcp-discovery-enabled", havingValue = "true", matchIfMissing = false)
	public DiscoverToolsTool discoverToolsTool(McpRegistryDiscoveryClient discoveryClient,
	                                           McpToolCallbackFactory callbackFactory,
	                                           ToolRegistryProperties properties,
	                                           ObjectMapper objectMapper,
	                                           ObservationRegistry observationRegistry) {
		log.info("[McpDiscoveryAutoConfiguration] T2d 特性开关已启用，初始化 DiscoverToolsTool");
		return new DiscoverToolsTool(discoveryClient, callbackFactory, properties, objectMapper, observationRegistry);
	}
}
