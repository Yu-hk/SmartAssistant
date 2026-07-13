/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.model.tier;

import com.example.smartassistant.common.rag.pipeline.QueryComplexityClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
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

    private static final Logger log = LoggerFactory.getLogger(TierModelAutoConfiguration.class);

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
     *
     * <p>当 {@code tier.canary-model} 配置非空时，构建灰度模型（委托同一 Ollama 实例、覆盖模型名与温度），
     * 作为灰度流量的首选节点；命中灰度比例的请求先试灰度模型，失败自动回退到正常档位降级链。</p>
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
            log.info("[TierModelAutoConfiguration] 灰度模型已就绪: {} (ratio={})", canaryName, props.getCanaryRatio());
        }
        return new TieredModelRouter(
                new QueryComplexityClassifier(),
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
