package com.example.smartassistant.common.tool.provider;

import com.example.smartassistant.common.tool.client.ToolRegistryClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * {@link ToolProvider} 自动配置。
 * <p>
 * 当容器中不存在其他 {@link ToolProvider} Bean 时，
 * 自动创建 {@link SpringToolProvider} 实例。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
@AutoConfiguration
public class ToolProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ToolProvider.class)
    public ToolProvider toolProvider(ApplicationContext applicationContext,
                                     ToolRegistryClient registryClient) {
        return new SpringToolProvider(applicationContext, registryClient);
    }
}
