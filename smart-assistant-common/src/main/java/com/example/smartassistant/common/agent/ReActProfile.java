/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

/**
 * SmartReActAgent 运行时画像（入口分级配置）。
 *
 * <p>将原先散落在 {@link SmartReActAgent} 上的
 * {@code maxIterations / timeoutMs / tokenBudgetRatio / contextWindow /
 * toolTimeoutMs / maxConcurrency} 收敛为不可变值对象，支持按入口
 * （order / general / product / mcp）差异化配置，实现文章《生产级 Agent 架构实战》
 * 提出的「步数 / 预算按入口分级」。</p>
 *
 * <p>所有 {@code withXxx} 返回新的 {@code ReActProfile}，保持不可变语义。</p>
 */
public record ReActProfile(
        int maxIterations,
        long timeoutMs,
        double tokenBudgetRatio,
        int contextWindow,
        long toolTimeoutMs,
        int maxConcurrency) {

    /**
     * 默认画像：与历史硬编码值一致
     * （maxIterations=10 / timeout=60s / 预算 0.8×128k / 并发 4 / 工具超时 30s），
     * 保证未配置任何入口画像时行为完全不变。
     */
    public static final ReActProfile DEFAULT = new ReActProfile(10, 60_000, 0.8, 128_000, 30_000, 4);

    public ReActProfile withMaxIterations(int v) {
        return new ReActProfile(v, timeoutMs, tokenBudgetRatio, contextWindow, toolTimeoutMs, maxConcurrency);
    }

    public ReActProfile withTimeoutMs(long v) {
        return new ReActProfile(maxIterations, v, tokenBudgetRatio, contextWindow, toolTimeoutMs, maxConcurrency);
    }

    public ReActProfile withTokenBudgetRatio(double v) {
        return new ReActProfile(maxIterations, timeoutMs, v, contextWindow, toolTimeoutMs, maxConcurrency);
    }

    public ReActProfile withContextWindow(int v) {
        return new ReActProfile(maxIterations, timeoutMs, tokenBudgetRatio, v, toolTimeoutMs, maxConcurrency);
    }

    public ReActProfile withToolTimeoutMs(long v) {
        return new ReActProfile(maxIterations, timeoutMs, tokenBudgetRatio, contextWindow, v, maxConcurrency);
    }

    public ReActProfile withMaxConcurrency(int v) {
        return new ReActProfile(maxIterations, timeoutMs, tokenBudgetRatio, contextWindow, toolTimeoutMs, v);
    }
}
