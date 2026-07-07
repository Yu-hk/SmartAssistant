/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.example.smartassistant.common.rag.eval.ContextFaithfulnessChecker;
import com.example.smartassistant.common.rag.eval.HallucinationDetector;
import com.example.smartassistant.common.rag.eval.RAGEvaluationResult;
import com.example.smartassistant.common.rag.eval.RetrievalMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EvalGate} 单元验证：绝对阈值（通过/失败）与基线回归两条路径。
 */
class EvalGateTest {

    private static final ContextFaithfulnessChecker.FaithfulnessResult FAITH_OK =
            new ContextFaithfulnessChecker.FaithfulnessResult(true, 1.0, List.of(), "ok");
    private static final HallucinationDetector.HallucinationResult HALLUC_OK =
            new HallucinationDetector.HallucinationResult(false, 0.0, List.of(), "ok");
    private static final HallucinationDetector.HallucinationResult HALLUC_BAD =
            new HallucinationDetector.HallucinationResult(true, 0.9,
                    List.of(new HallucinationDetector.HallucinationClaim("数字断言", "x", "y", 0.9)), "bad");

    private static RAGEvaluationResult rag(double ndcg, double hallucRate, boolean halluc) {
        List<RetrievalMetrics.MetricResult> metrics =
                List.of(new RetrievalMetrics.MetricResult("nDCG", ndcg, 5));
        HallucinationDetector.HallucinationResult h = halluc
                ? HALLUC_BAD : HALLUC_OK;
        return new RAGEvaluationResult("q", metrics, FAITH_OK, h, 1L);
    }

    @Test
    void absoluteThresholdsPassWhenAllGood() {
        EvalGate gate = new EvalGate();
        EvalGateConfig cfg = new EvalGateConfig(); // 默认阈值
        List<RAGEvaluationResult> rag = List.of(rag(1.0, 0.0, false), rag(0.9, 0.0, false));
        EvalGate.GateResult r = gate.evaluate(rag, List.of(), cfg, null);
        assertTrue(r.passed(), "全部指标达标应通过，违规: " + r.violations());
    }

    @Test
    void absoluteThresholdsFailWhenHallucinationTooHigh() {
        EvalGate gate = new EvalGate();
        EvalGateConfig cfg = new EvalGateConfig();
        List<RAGEvaluationResult> rag = List.of(rag(1.0, 0.0, false), rag(1.0, 0.9, true));
        EvalGate.GateResult r = gate.evaluate(rag, List.of(), cfg, null);
        assertFalse(r.passed(), "平均幻觉率超阈值应失败");
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("幻觉率")),
                "应报告幻觉率违规: " + r.violations());
    }

    @Test
    void baselineRegressionIsDetected() {
        EvalGate gate = new EvalGate();
        EvalGateConfig cfg = new EvalGateConfig(); // compareToBaseline=true, maxRegression=0.05

        // 当前质量明显低于基线 → 应判定回归
        List<RAGEvaluationResult> rag = List.of(rag(0.5, 0.0, false)); // avgComposite ≈ 0.8

        EvalBaseline baseline = new EvalBaseline();
        baseline.metrics = Map.of(
                "rag.passRate", 1.0,
                "rag.avgComposite", 1.0,          // 基线 1.0 vs 当前 0.8 → 回归
                "rag.avgNdcg", 1.0,              // 基线 1.0 vs 当前 0.5 → 回归
                "rag.avgFaithfulness", 1.0,
                "rag.avgHallucinationRate", 0.0,
                "agent.passRate", 0.0,           // 当前 agent 无结果（与空运行一致），不构成回归
                "agent.avgComposite", 0.0
        );

        EvalGate.GateResult r = gate.evaluate(rag, List.of(), cfg, baseline);
        assertFalse(r.passed(), "相对基线严重回归应失败");
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("基线回归")),
                "应报告基线回归: " + r.violations());
    }

    @Test
    void baselineStableWithinTolerancePasses() {
        EvalGate gate = new EvalGate();
        EvalGateConfig cfg = new EvalGateConfig();
        List<RAGEvaluationResult> rag = List.of(rag(0.98, 0.0, false)); // avgComposite ≈ 0.992

        EvalBaseline baseline = new EvalBaseline();
        baseline.metrics = Map.of(
                "rag.passRate", 1.0,
                "rag.avgComposite", 1.0,
                "rag.avgNdcg", 1.0,
                "rag.avgFaithfulness", 1.0,
                "rag.avgHallucinationRate", 0.0,
                "agent.passRate", 0.0,           // 当前 agent 无结果（与空运行一致），不构成回归
                "agent.avgComposite", 0.0
        );

        EvalGate.GateResult r = gate.evaluate(rag, List.of(), cfg, baseline);
        assertTrue(r.passed(), "基线内微小波动应放行，违规: " + r.violations());
    }
}
