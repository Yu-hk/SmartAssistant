/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

/**
 * RAG 检索质量结果 —— 统一模型，供 Product/Order 等模块共享。
 *
 * <p>包含检索内容、归一化质量分数、高质量标志和结构化拒绝信息。</p>
 */
public class RetrievalQualityResult {

    /** 检索到的内容（可能为空或部分） */
    private String content;

    /** 归一化质量分数 [0.0, 1.0]，由 RRF 融合或意图判定计算得出 */
    private double normalizedScore;

    /** 是否高质量（normalizedScore >= threshold） */
    private boolean highQuality;

    /** 结果质量标签，用于日志/监控 */
    private QualityLabel qualityLabel;

    /**
     * 结构化拒绝原因。
     * <ul>
     *   <li>null — 无拒绝，检索正常</li>
     *   <li>"INSUFFICIENT_EVIDENCE" — 证据不足，无法准确回答</li>
     *   <li>"NO_RELEVANT_DATA" — 知识库中无相关数据</li>
     * </ul>
     */
    private String rejectionCode;

    /** 给用户的拒绝消息 */
    private String rejectionMessage;

    public RetrievalQualityResult() {
        this.content = "";
        this.normalizedScore = 0.0;
        this.highQuality = false;
        this.qualityLabel = QualityLabel.UNKNOWN;
        this.rejectionCode = null;
        this.rejectionMessage = null;
    }

    // ═══════════════════════════════════════════════════════════
    // 工厂方法
    // ═══════════════════════════════════════════════════════════

    /** 高质量检索结果 */
    public static RetrievalQualityResult highQuality(String content, double normalizedScore) {
        RetrievalQualityResult r = new RetrievalQualityResult();
        r.content = content;
        r.normalizedScore = Math.min(1.0, Math.max(0.0, normalizedScore));
        r.highQuality = true;
        r.qualityLabel = r.normalizedScore >= 0.7 ? QualityLabel.EXCELLENT
                : r.normalizedScore >= 0.5 ? QualityLabel.GOOD : QualityLabel.FAIR;
        return r;
    }

    /** 低质量检索结果（证据不足） */
    public static RetrievalQualityResult insufficientEvidence(String content, double normalizedScore, String detail) {
        RetrievalQualityResult r = new RetrievalQualityResult();
        r.content = content;
        r.normalizedScore = Math.min(1.0, Math.max(0.0, normalizedScore));
        r.highQuality = false;
        r.qualityLabel = QualityLabel.INSUFFICIENT;
        r.rejectionCode = "INSUFFICIENT_EVIDENCE";
        r.rejectionMessage = buildRejectionMessage(detail, normalizedScore);
        return r;
    }

    /** 无相关数据 */
    public static RetrievalQualityResult noData(String queryDetail) {
        RetrievalQualityResult r = new RetrievalQualityResult();
        r.content = "";
        r.normalizedScore = 0.0;
        r.highQuality = false;
        r.qualityLabel = QualityLabel.NO_DATA;
        r.rejectionCode = "NO_RELEVANT_DATA";
        r.rejectionMessage = "抱歉，数据库中未找到与「" + queryDetail + "」相关的信息。"
                + "请尝试更换关键词或联系人工客服。";
        return r;
    }

    // ═══════════════════════════════════════════════════════════
    // 质量标签枚举
    // ═══════════════════════════════════════════════════════════

    public enum QualityLabel {
        /** 高质量（≥0.7） */
        EXCELLENT,
        /** 良好（≥0.5） */
        GOOD,
        /** 一般（≥0.3） */
        FAIR,
        /** 证据不足（<0.3） */
        INSUFFICIENT,
        /** 无数据 */
        NO_DATA,
        /** 未知（默认） */
        UNKNOWN
    }

    // ═══════════════════════════════════════════════════════════
    // 构建拒绝消息
    // ═══════════════════════════════════════════════════════════

    private static String buildRejectionMessage(String detail, double score) {
        StringBuilder sb = new StringBuilder();
        sb.append("抱歉，暂时无法回答您的问题。");

        if (detail != null && !detail.isBlank()) {
            sb.append(" ").append(detail);
        }

        if (score > 0) {
            sb.append("（检索置信度：").append(String.format("%.0f", score * 100)).append("%）");
        }

        sb.append(" 请确认问题后再试，或联系人工客服。");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════
    // 便捷查询
    // ═══════════════════════════════════════════════════════════

    /** 是否可以用于回答（高质量或 FAIR 以上均可尝试） */
    public boolean isAnswerable() {
        return highQuality || qualityLabel == QualityLabel.FAIR;
    }

    /** 是否触发了结构化拒绝 */
    public boolean isRejected() {
        return rejectionCode != null;
    }

    // ═══════════════════════════════════════════════════════════
    // Getters & Setters
    // ═══════════════════════════════════════════════════════════

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public double getNormalizedScore() { return normalizedScore; }
    public void setNormalizedScore(double normalizedScore) {
        this.normalizedScore = Math.min(1.0, Math.max(0.0, normalizedScore));
    }

    public boolean isHighQuality() { return highQuality; }
    public void setHighQuality(boolean highQuality) { this.highQuality = highQuality; }

    public QualityLabel getQualityLabel() { return qualityLabel; }
    public void setQualityLabel(QualityLabel qualityLabel) { this.qualityLabel = qualityLabel; }

    public String getRejectionCode() { return rejectionCode; }
    public void setRejectionCode(String rejectionCode) { this.rejectionCode = rejectionCode; }

    public String getRejectionMessage() { return rejectionMessage; }
    public void setRejectionMessage(String rejectionMessage) { this.rejectionMessage = rejectionMessage; }
}
