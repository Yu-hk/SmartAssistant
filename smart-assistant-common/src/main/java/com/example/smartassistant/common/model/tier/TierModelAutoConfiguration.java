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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.EnumMap;
import java.util.Map;

/**
 * G3 Tier 多模型路由自动配置（common 中台，供所有服务复用）。
 *
 * <p><b>关键约束</b>：本配置不绑定具体模型供应商，也不注册新的 ChatModel Spring Bean。
 * 三档模型以 {@link DelegatingOptionsChatModel} 形式在 {@link TierModelRegistry} 内部持有，
 * 复用应用中唯一的供应商 ChatModel，并在请求级覆盖模型名与温度。</p>
 *
 * <p>仅在存在 {@link ChatModel} 时激活；无模型 API 的环境（如纯 HTTP 转发的 user 服务）
 * 不创建任何 bean，调用方以 {@code @Autowired(required = false)} 优雅降级。</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration"
})
@ConditionalOnClass(ChatModel.class)
@ConditionalOnSingleCandidate(ChatModel.class)
@EnableConfigurationProperties(TieredModelRouterProperties.class)
public class TierModelAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TierModelAutoConfiguration.class);

    /**
     * 档位模型注册表——内部持有三档 DelegatingOptionsChatModel（非 Spring Bean）。
     */
    @Bean
    public TierModelRegistry tierModelRegistry(ChatModel chatModel,
                                               TieredModelRouterProperties props) {
        Map<ModelTier, TierModelRegistry.TierModelEntry> m = new EnumMap<>(ModelTier.class);
        m.put(ModelTier.LIGHT, entry(chatModel, props.getLight()));
        m.put(ModelTier.STANDARD, entry(chatModel, props.getStandard()));
        m.put(ModelTier.HEAVY, entry(chatModel, props.getHeavy()));
        return new TierModelRegistry(m);
    }

    /**
     * 统一模型接入层路由器——复用 {@link QueryComplexityClassifier} 做动态路由 + 平滑降级。
     *
     * <p>当 {@code tier.canary-model} 配置非空时，构建灰度模型（委托同一供应商实例、覆盖模型名与温度），
     * 作为灰度流量的首选节点；命中灰度比例的请求先试灰度模型，失败自动回退到正常档位降级链。</p>
     */
    @Bean
    public TieredModelRouter tieredModelRouter(TierModelRegistry registry,
                                               TieredModelRouterProperties props,
                                               ChatModel chatModel,
                                               @Autowired(required = false) MeterRegistry meterRegistry) {
        ChatModel canaryModel = null;
        String canaryName = props.getCanaryModel();
        if (canaryName != null && !canaryName.isBlank()) {
            ChatOptions canaryOptions = providerOptions(
                    chatModel, canaryName, props.getCanaryTemperature());
            canaryModel = new DelegatingOptionsChatModel(chatModel, canaryOptions);
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

    private TierModelRegistry.TierModelEntry entry(ChatModel chatModel,
                                                   TieredModelRouterProperties.TierConfig cfg) {
        ChatOptions options = providerOptions(chatModel, cfg.getModel(), cfg.getTemperature());
        return new TierModelRegistry.TierModelEntry(
                new DelegatingOptionsChatModel(chatModel, options), cfg.getModel());
    }

    /**
     * 从供应商模型自己的 Options 类型派生档位参数。
     * OpenAI、DeepSeek 等实现会在调用时强制转换为各自 Options 子类，直接使用
     * {@link ChatOptions#builder()} 产生的 DefaultChatOptions 会导致 ClassCastException。
     */
    private ChatOptions providerOptions(ChatModel chatModel, String model, double temperature) {
        ChatOptions defaults = chatModel.getDefaultOptions();
        ChatOptions.Builder<?> builder = defaults != null ? defaults.mutate() : ChatOptions.builder();
        return builder.model(model).temperature(temperature).build();
    }
}
