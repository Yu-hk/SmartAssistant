/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval.grader;

import com.example.smartassistant.common.eval.AgentEvaluationResult;
import com.example.smartassistant.common.eval.EvaluationReportService.AgentTestSpec;

/**
 * 评测打分器 — 瀑布流中的一环（规则 → LLM → 人工）。
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public interface Grader {
    GraderResult grade(AgentEvaluationResult result, AgentTestSpec spec);
}
