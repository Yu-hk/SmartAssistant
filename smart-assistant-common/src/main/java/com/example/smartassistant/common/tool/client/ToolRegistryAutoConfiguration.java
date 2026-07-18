package com.example.smartassistant.common.tool.client;

import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Tool Registry 客户端自动配置。
 * <p>
 * 当 {@code tool-registry.url} 配置存在且 {@code java.net.http.HttpClient} 可用时，
 * 自动创建 {@link ToolRegistryClient} Bean。
 * 各 Agent 模块直接注入使用。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
@AutoConfiguration
@ConditionalOnBean({ToolGateway.class, ToolRegistry.class})
@EnableConfigurationProperties(ToolRegistryProperties.class)
public class ToolRegistryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistryClient toolRegistryClient(
            ToolRegistryProperties properties,
            ObjectMapper objectMapper,
            ToolGateway gateway,
            ToolRegistry toolRegistry) {
        log.info("[ToolRegistryAutoConfiguration] 初始化 ToolRegistryClient: url={}, cacheTtl={}s",
                properties.getUrl(), properties.getCacheTtlSeconds());
        return new ToolRegistryClient(properties, objectMapper, gateway, toolRegistry);
    }
}
