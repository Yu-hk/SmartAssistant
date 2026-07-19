/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.config;

import com.example.smartassistant.common.model.tier.DelegatingOptionsChatModel;
import com.example.smartassistant.common.model.tier.ModelTier;
import com.example.smartassistant.common.model.tier.TierModelRegistry;
import com.example.smartassistant.common.model.tier.TieredModelRouter;
import com.example.smartassistant.common.model.tier.TieredModelRouterProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * G3 Tier 多模型路由配置（Router 模块侧，用户配置）。
 *
 * <p><b>为何放在 Router 而非 common 自动配置</b>：common 中的 {@code TierModelAutoConfiguration}
 * 作为 auto-configuration 在排序批次中求值，{@code @ConditionalOnBean(OllamaChatModel.class)}
 * 因跨 jar 自动配置排序不可靠而稳定失败。本配置位于 Router 应用上下文（被 {@code @ComponentScan}
 * 扫描的用户配置），在所有 auto-configuration 之后处理，{@code OllamaChatModel} 此时必然已注册，
 * 直接注入即可，无需脆弱的 {@code @ConditionalOnBean}。</p>
 *
 * <p>仅 Router 依赖 {@link TieredModelRouter}（经由 {@code ModelRouterService}），故注册收敛于此。</p>
 */
@Configuration
@EnableConfigurationProperties(TieredModelRouterProperties.class)
public class TierModelRouterConfig {

    private static final Logger log = LoggerFactory.getLogger(TierModelRouterConfig.class);

    /**
     * 档位模型注册表——内部持有三档 DelegatingOptionsChatModel（非 Spring Bean）。
     */
    @Bean
    public TierModelRegistry tierModelRegistry(OllamaChatModel ollama,
                                               TieredModelRouterProperties props) {
        Map<ModelTier, TierModelRegistry.TierModelEntry> m = new EnumMap<>(ModelTier.class);
        m.put(ModelTier.LIGHT, entry(ollama, props.getLight()));
        m.put(ModelTier.STANDARD, entry(ollama, props.getStandard()));
        m.put(ModelTier.HEAVY, entry(ollama, props.getHeavy()));
        return new TierModelRegistry(m);
    }

    /**
     * 统一模型接入层路由器——复用查询复杂度分类做动态路由 + 平滑降级。
     */
    @Bean
    public TieredModelRouter tieredModelRouter(TierModelRegistry registry,
                                               TieredModelRouterProperties props,
                                               OllamaChatModel ollama,
                                               @Autowired(required = false) MeterRegistry meterRegistry) {
        ChatModel canaryModel = null;
        String canaryName = props.getCanaryModel();
        if (canaryName != null && !canaryName.isBlank()) {
            OllamaOptions canaryOptions = OllamaOptions.builder()
                    .model(canaryName)
                    .temperature(props.getCanaryTemperature())
                    .build();
            canaryModel = new DelegatingOptionsChatModel(ollama, canaryOptions);
            log.info("[TierModelRouterConfig] 灰度模型已就绪: {} (ratio={})", canaryName, props.getCanaryRatio());
        }
        log.info("[TierModelRouterConfig] TieredModelRouter 已注册（三档：{}/{}/{}）",
                props.getLight().getModel(), props.getStandard().getModel(), props.getHeavy().getModel());
        return new TieredModelRouter(
                new com.example.smartassistant.common.rag.pipeline.QueryComplexityClassifier(),
                registry,
                props.getIntentOverrides(),
                props.isDegradationEnabled(),
                props.getCanaryRatio(),
                props.getCanaryModel(),
                canaryModel,
                meterRegistry);
    }

    private TierModelRegistry.TierModelEntry entry(OllamaChatModel ollama,
                                                   TieredModelRouterProperties.TierConfig cfg) {
        OllamaOptions options = OllamaOptions.builder()
                .model(cfg.getModel())
                .temperature(cfg.getTemperature())
                .build();
        return new TierModelRegistry.TierModelEntry(
                new DelegatingOptionsChatModel(ollama, options), cfg.getModel());
    }
}
