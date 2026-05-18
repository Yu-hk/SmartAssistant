/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

/**
 * 反思器评估结果
 *
 * <p>封装反思器对 Agent 回复的质量评分与处理建议。</p>
 *
 * @author SmartAssistant
 * @since 2026-05-18
 */
public class ReflectionResult {

    /**
     * 是否通过质量门禁（score >= threshold）
     */
    private final boolean acceptable;

    /**
     * 质量评分（0.0 ~ 1.0）
     */
    private final double score;

    /**
     * 评估原因（用于日志/调试）
     */
    private final String reason;

    /**
     * 建议的重试 Agent 名称（null 表示无需重试）
     */
    private final String suggestedAgent;

    public ReflectionResult(boolean acceptable, double score, String reason) {
        this(acceptable, score, reason, null);
    }

    public ReflectionResult(boolean acceptable, double score, String reason, String suggestedAgent) {
        this.acceptable = acceptable;
        this.score = score;
        this.reason = reason;
        this.suggestedAgent = suggestedAgent;
    }

    public boolean isAcceptable() {
        return acceptable;
    }

    public double getScore() {
        return score;
    }

    public String getReason() {
        return reason;
    }

    public String getSuggestedAgent() {
        return suggestedAgent;
    }

    @Override
    public String toString() {
        return "ReflectionResult{" +
                "acceptable=" + acceptable +
                ", score=" + String.format("%.2f", score) +
                ", reason='" + reason + '\'' +
                ", suggestedAgent='" + suggestedAgent + '\'' +
                '}';
    }
}
