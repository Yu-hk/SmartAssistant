/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Token 预算配置（绑定到 {@code budget} 前缀）。
 * <p>
 * 支持通过 Nacos Config 运行时热更新。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Component
@ConfigurationProperties(prefix = "budget")
public class BudgetConfig {

    /** 每轮对话最大 Token 消耗（输入+输出） */
    private int maxTokensPerSession = 16384;

    /** 每轮对话最大工具调用次数 */
    private int maxToolCallsPerSession = 20;

    /** Agent 最大迭代轮次 */
    private int maxRoundsPerSession = 10;

    /** 单次 LLM 调用最大输出 Token */
    private int maxTokensPerCall = 2048;

    /** 单次工具调用超时（毫秒） */
    private int toolTimeoutMs = 15000;

    /** 是否启用预算控制 */
    private boolean enabled = true;

    /** 超限后的降级行为：TRUNCATE / FALLBACK / TERMINATE */
    private String onExceeded = "TRUNCATE";

    // ═══════════════════════════════════════════════════════════
    // P2 用户级 Token 配额（Redis 持久化）
    // ═══════════════════════════════════════════════════════════

    /** 每个用户每天最大 Token 消耗（0 表示不限） */
    private int maxTokensPerUserPerDay = 0;

    /** 每个用户每天最大 LLM 调用次数（0 表示不限） */
    private int maxCallsPerUserPerDay = 0;

    /** Token 配额重置间隔：DAILY / HOURLY */
    private String quotaResetInterval = "DAILY";

    // ===== Getters & Setters =====

    public int getMaxTokensPerSession() { return maxTokensPerSession; }
    public void setMaxTokensPerSession(int maxTokensPerSession) { this.maxTokensPerSession = maxTokensPerSession; }

    public int getMaxToolCallsPerSession() { return maxToolCallsPerSession; }
    public void setMaxToolCallsPerSession(int maxToolCallsPerSession) { this.maxToolCallsPerSession = maxToolCallsPerSession; }

    public int getMaxRoundsPerSession() { return maxRoundsPerSession; }
    public void setMaxRoundsPerSession(int maxRoundsPerSession) { this.maxRoundsPerSession = maxRoundsPerSession; }

    public int getMaxTokensPerCall() { return maxTokensPerCall; }
    public void setMaxTokensPerCall(int maxTokensPerCall) { this.maxTokensPerCall = maxTokensPerCall; }

    public int getToolTimeoutMs() { return toolTimeoutMs; }
    public void setToolTimeoutMs(int toolTimeoutMs) { this.toolTimeoutMs = toolTimeoutMs; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getOnExceeded() { return onExceeded; }
    public void setOnExceeded(String onExceeded) { this.onExceeded = onExceeded; }

    public int getMaxTokensPerUserPerDay() { return maxTokensPerUserPerDay; }
    public void setMaxTokensPerUserPerDay(int maxTokensPerUserPerDay) { this.maxTokensPerUserPerDay = maxTokensPerUserPerDay; }

    public int getMaxCallsPerUserPerDay() { return maxCallsPerUserPerDay; }
    public void setMaxCallsPerUserPerDay(int maxCallsPerUserPerDay) { this.maxCallsPerUserPerDay = maxCallsPerUserPerDay; }

    public String getQuotaResetInterval() { return quotaResetInterval; }
    public void setQuotaResetInterval(String quotaResetInterval) { this.quotaResetInterval = quotaResetInterval; }
}
