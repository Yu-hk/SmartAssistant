/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.model.tier;

import com.example.smartassistant.common.rag.pipeline.QueryComplexityClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * G3 Tier 多模型路由自动配置（common 中台，供所有服务复用）。
 *
 * <p><b>关键约束</b>：为避免与 common 内 {@code @ConditionalOnBean(ChatModel.class)} 的自动配置
 * （如 RagGraphAutoConfiguration）争抢无限定 {@code ChatModel} 注入，本配置<b>不注册新的 ChatModel Spring Bean</b>。
 * 三档模型以 {@link DelegatingOptionsChatModel} 形式在 {@link TierModelRegistry} 内部持有，
 * 仅注入 {@link OllamaChatModel}（具体类型，唯一且明确）作为委托底层。</p>
 *
 * <p>仅在存在 {@link OllamaChatModel} 时激活；无 Ollama 的环境（如纯 HTTP 转发的 user 服务）
 * 不创建任何 bean，调用方以 {@code @Autowired(required = false)} 优雅降级。</p>
 */
@Configuration
@ConditionalOnBean(OllamaChatModel.class)
@EnableConfigurationProperties(TieredModelRouterProperties.class)
public class TierModelAutoConfiguration {

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
     * 统一模型接入层路由器——复用 {@link QueryComplexityClassifier} 做动态路由 + 平滑降级。
     */
    @Bean
    public TieredModelRouter tieredModelRouter(TierModelRegistry registry,
                                               TieredModelRouterProperties props,
                                               @Autowired(required = false) MeterRegistry meterRegistry) {
        return new TieredModelRouter(
                new QueryComplexityClassifier(),
                registry,
                props.getIntentOverrides(),
                props.isDegradationEnabled(),
                meterRegistry);
    }

    private TierModelRegistry.TierModelEntry entry(OllamaChatModel ollama,
                                                   TieredModelRouterProperties.TierConfig cfg) {
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(cfg.getModel())
                .temperature(cfg.getTemperature())
                .build();
        return new TierModelRegistry.TierModelEntry(
                new DelegatingOptionsChatModel(ollama, options), cfg.getModel());
    }
}
