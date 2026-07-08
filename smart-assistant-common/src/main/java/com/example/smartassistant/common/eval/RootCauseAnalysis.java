/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import java.util.List;
import java.util.Map;

/**
 * 根因分析结果 — 对应文章《Agent 评测体系》§10 的「根因 5 步 + 聚类定责 + 落盘」。
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public record RootCauseAnalysis(
        int evidenceCount,                                       // 失败证据数
        Map<FailureSignature.FailureType, List<String>> clusters, // 按失败类型聚类的 caseId
        List<Diagnosis> diagnoses,                               // 各簇诊断
        boolean persisted) {                                     // 是否已落盘

    /** 单簇诊断：失败类型 → 根因 + 责任角色 + 修复建议 + 样本。 */
    public record Diagnosis(
            FailureSignature.FailureType type,
            String rootCause,
            RootCauseTag responsibleRole,
            String recommendation,
            List<String> sampleCaseIds) {
    }
}
