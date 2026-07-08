/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import java.util.Objects;

/**
 * 失败签名 — 将一次失败结果归一到可聚类的失败类型（文章 §10 「证据收敛」）。
 *
 * <p>从维度分 + 错误标志推导失败类型，相同类型的失败聚为一簇，定位共因。</p>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public record FailureSignature(String caseId, FailureType type, String signatureHash) {

    /** 失败类型枚举（用于聚类与诊断映射）。 */
    public enum FailureType {
        WRONG_INTENT,       // 意图识别错误
        WRONG_TOOLS,        // 工具选择错误
        MISSING_KEYWORDS,   // 回复缺失关键信息
        ERROR,              // 执行异常
        LOW_OVERALL,        // 综合分低但无明确单点
        UNKNOWN
    }

    /** 从评测结果推导签名。 */
    public static FailureSignature of(AgentEvaluationResult r) {
        FailureType type;
        if (r.isHasError()) {
            type = FailureType.ERROR;
        } else if (r.getIntentMatchScore() < 1.0) {
            type = FailureType.WRONG_INTENT;
        } else if (r.getToolSelectionAccuracy() < 1.0) {
            type = FailureType.WRONG_TOOLS;
        } else if (r.getResponseQualityScore() < 1.0) {
            type = FailureType.MISSING_KEYWORDS;
        } else if (r.getCompositeScore() < 0.6) {
            type = FailureType.LOW_OVERALL;
        } else {
            type = FailureType.UNKNOWN;
        }
        String hash = Integer.toHexString(Objects.hash(r.getCaseId(), type.name()));
        return new FailureSignature(r.getCaseId(), type, hash);
    }
}
