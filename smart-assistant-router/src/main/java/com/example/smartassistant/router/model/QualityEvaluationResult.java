/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

/**
 * LLM-as-Judge 质量评估结果。
 * <p>
 * 由 {@code QualityEvaluationService} 产出的多维度评分，覆盖相关性、完整性、
 * 幻觉检测和实用性。与 {@code ReflectionResult} 互补——Reflection 使用纯规则
 * 做浅层质量检查，此模型使用 LLM 做深层语义质量评估。
 * </p>
 *
 * @see com.example.smartassistant.router.service.quality.QualityEvaluationService
 * @see ReflectionResult
 */
public class QualityEvaluationResult {

    /** 相关性评分（0.0~1.0）：回复是否直接回答了用户问题 */
    private final double relevance;

    /** 完整性评分（0.0~1.0）：回复是否覆盖了问题的所有维度 */
    private final double completeness;

    /** 幻觉评分（0.0~1.0）：1.0=无幻觉，0.0=明显捏造 */
    private final double hallucination;

    /** 实用性评分（0.0~1.0）：回复是否清晰、可操作 */
    private final double helpfulness;

    /** 综合评分（0.0~1.0）：各维度加权平均 */
    private final double overall;

    /** 评估理由（LLM 生成的简要说明） */
    private final String reason;

    /** LLM 原始响应（调试用） */
    private final String rawResponse;

    public QualityEvaluationResult(double relevance, double completeness,
                                   double hallucination, double helpfulness,
                                   double overall, String reason, String rawResponse) {
        this.relevance = clamp(relevance);
        this.completeness = clamp(completeness);
        this.hallucination = clamp(hallucination);
        this.helpfulness = clamp(helpfulness);
        this.overall = clamp(overall);
        this.reason = reason != null ? reason : "";
        this.rawResponse = rawResponse;
    }

    /** 构造一个评估失败的默认结果（LLM 调用异常时使用） */
    public static QualityEvaluationResult failed(String errorMessage) {
        return new QualityEvaluationResult(0.0, 0.0, 0.0, 0.0, 0.0,
                "Evaluation failed: " + errorMessage, null);
    }

    /** 构造一个评估跳过时的中性结果 */
    public static QualityEvaluationResult skipped() {
        return new QualityEvaluationResult(0.0, 0.0, 1.0, 0.0, 0.7, "Skipped", null);
    }

    /** 是否通过质量评估 */
    public boolean isPassing(double threshold) {
        return overall >= threshold && hallucination >= threshold;
    }

    /** LLM 调用是否成功完成 */
    public boolean isCompleted() {
        return rawResponse != null && !rawResponse.isBlank();
    }

    /** 是否检测到幻觉风险 */
    public boolean hasHallucinationRisk(double threshold) {
        return hallucination < threshold;
    }

    // ------- Getters -------

    public double getRelevance() { return relevance; }
    public double getCompleteness() { return completeness; }
    public double getHallucination() { return hallucination; }
    public double getHelpfulness() { return helpfulness; }
    public double getOverall() { return overall; }
    public String getReason() { return reason; }
    public String getRawResponse() { return rawResponse; }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    @Override
    public String toString() {
        return "QualityEvaluationResult{overall=" + String.format("%.2f", overall)
                + ", relevance=" + String.format("%.2f", relevance)
                + ", completeness=" + String.format("%.2f", completeness)
                + ", hallucination=" + String.format("%.2f", hallucination)
                + ", helpfulness=" + String.format("%.2f", helpfulness)
                + ", reason='" + reason + "'}";
    }
}
