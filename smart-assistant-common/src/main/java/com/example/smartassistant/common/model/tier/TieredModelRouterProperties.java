/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.model.tier;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * G3 Tier 多模型路由配置属性，绑定前缀 {@code tier}。
 *
 * <p>每档可独立配置模型名与温度；本地环境可让三档指向同一 Ollama 实例的不同模型名，
 * 云端环境可将 heavy 指向 DeepSeek/GPT 等强模型。{@code intent-overrides} 支持按意图强制升档。</p>
 */
@ConfigurationProperties(prefix = "tier")
public class TieredModelRouterProperties {

    private TierConfig light = new TierConfig("qwen2.5:3b", 0.1);
    private TierConfig standard = new TierConfig("deepseek-r1:7b", 0.3);
    private TierConfig heavy = new TierConfig("deepseek-r1:7b", 0.2);

    /** 意图 → 强制档位（如退款投诉类意图强制 HEAVY）。 */
    private Map<String, ModelTier> intentOverrides = new HashMap<>();

    /** 是否启用平滑降级（关闭后只在选定档位调用，失败即抛异常）。 */
    private boolean degradationEnabled = true;

    // ═══════════════════════════════════════════════════════════
    // ⭐ 灰度发布（canary-ratio）
    // ═══════════════════════════════════════════════════════════

    /** 灰度比例（0.0~1.0），新模型上线时逐步放量。0.0=全量旧模型，1.0=全量新模型。 */
    private double canaryRatio = 0.0;

    /** 灰度模型名（canary-ratio > 0 时，按比例切流到该模型）。 */
    private String canaryModel = "";

    public static class TierConfig {
        private String model;
        private double temperature;

        public TierConfig() {
        }

        public TierConfig(String model, double temperature) {
            this.model = model;
            this.temperature = temperature;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }

    public TierConfig getLight() {
        return light;
    }

    public void setLight(TierConfig light) {
        this.light = light;
    }

    public TierConfig getStandard() {
        return standard;
    }

    public void setStandard(TierConfig standard) {
        this.standard = standard;
    }

    public TierConfig getHeavy() {
        return heavy;
    }

    public void setHeavy(TierConfig heavy) {
        this.heavy = heavy;
    }

    public Map<String, ModelTier> getIntentOverrides() {
        return intentOverrides;
    }

    public void setIntentOverrides(Map<String, ModelTier> intentOverrides) {
        this.intentOverrides = intentOverrides;
    }

    public boolean isDegradationEnabled() {
        return degradationEnabled;
    }

    public void setDegradationEnabled(boolean degradationEnabled) {
        this.degradationEnabled = degradationEnabled;
    }

    public double getCanaryRatio() { return canaryRatio; }
    public void setCanaryRatio(double canaryRatio) { this.canaryRatio = canaryRatio; }
    public String getCanaryModel() { return canaryModel; }
    public void setCanaryModel(String canaryModel) { this.canaryModel = canaryModel; }
}
