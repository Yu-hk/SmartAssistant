/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 入口级 ReAct 画像配置属性。
 *
 * <p>示例（application.yml）：
 * <pre>
 * smartassistant:
 *   react:
 *     profiles:
 *       general:
 *         max-iterations: 6
 *         timeout-ms: 30000
 *       mcp:
 *         max-iterations: 8
 *         token-budget-ratio: 0.7
 * </pre>
 * 未填字段回退 {@link ReActProfile#DEFAULT}。</p>
 */
@ConfigurationProperties(prefix = "smartassistant.react")
public class ReActProfileProperties {

    private Map<String, ProfileConfig> profiles = new HashMap<>();

    public Map<String, ProfileConfig> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, ProfileConfig> profiles) {
        this.profiles = profiles;
    }

    /** 单入口可选配置（全部可选，缺省回退默认）。 */
    public static class ProfileConfig {
        private Integer maxIterations;
        private Long timeoutMs;
        private Double tokenBudgetRatio;
        private Integer contextWindow;
        private Long toolTimeoutMs;
        private Integer maxConcurrency;

        public Integer getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(Integer v) {
            this.maxIterations = v;
        }

        public Long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Long v) {
            this.timeoutMs = v;
        }

        public Double getTokenBudgetRatio() {
            return tokenBudgetRatio;
        }

        public void setTokenBudgetRatio(Double v) {
            this.tokenBudgetRatio = v;
        }

        public Integer getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(Integer v) {
            this.contextWindow = v;
        }

        public Long getToolTimeoutMs() {
            return toolTimeoutMs;
        }

        public void setToolTimeoutMs(Long v) {
            this.toolTimeoutMs = v;
        }

        public Integer getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(Integer v) {
            this.maxConcurrency = v;
        }
    }
}
