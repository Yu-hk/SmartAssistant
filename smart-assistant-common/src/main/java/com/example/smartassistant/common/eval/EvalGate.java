/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.example.smartassistant.common.rag.eval.RAGEvaluationResult;
import com.example.smartassistant.common.rag.eval.RetrievalMetrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测质量门禁 — 对黄金测试集的评测结果执行双重校验：
 *
 * <ol>
 *   <li><b>绝对阈值</b>：RAG 通过率 / 平均综合分 / nDCG / Faithfulness / 幻觉率必须落在配置区间内；</li>
 *   <li><b>基线回归</b>：当存在已提交 {@link EvalBaseline} 且 {@link EvalGateConfig#compareToBaseline}=true 时，
 *       任一关键指标相对基线变动超过 {@link EvalGateConfig#maxRegression} 即判定回归。</li>
 * </ol>
 *
 * <p>组件为纯计算、无外部依赖，可在 JUnit 单测与 CI 中确定性运行（无需 Redis/PG 等基础设施）。</p>
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
public class EvalGate {

    /**
     * 指标方向：{@code true} = 越高越好；{@code false} = 越低越好。
     * 键与 {@link #collectMetrics(List, List)} 产出的指标键一一对应。
     */
    private static final Map<String, Boolean> METRIC_DIRECTION = Map.of(
            "rag.passRate", true,
            "rag.avgComposite", true,
            "rag.avgNdcg", true,
            "rag.avgFaithfulness", true,
            "rag.avgHallucinationRate", false,
            "agent.passRate", true,
            "agent.avgComposite", true
    );

    /**
     * 从评测结果收集可比对指标快照。
     *
     * @param rag    RAG 评测结果列表（可空）
     * @param agent  Agent 评测结果列表（可空，离线时为"待执行"占位）
     * @return 指标键 → 数值 的快照（顺序稳定，便于基线比对）
     */
    public Map<String, Double> collectMetrics(List<RAGEvaluationResult> rag, List<AgentEvaluationResult> agent) {
        Map<String, Double> m = new LinkedHashMap<>();

        if (rag == null || rag.isEmpty()) {
            m.put("rag.passRate", 0.0);
            m.put("rag.avgComposite", 0.0);
            m.put("rag.avgNdcg", 0.0);
            m.put("rag.avgFaithfulness", 0.0);
            m.put("rag.avgHallucinationRate", 1.0);
        } else {
            double passRate = rag.stream().filter(RAGEvaluationResult::passed).count() / (double) rag.size();
            double avgComposite = rag.stream().mapToDouble(RAGEvaluationResult::compositeScore).average().orElse(0.0);
            double avgNdcg = rag.stream()
                    .flatMap(r -> r.getRetrievalMetrics().stream())
                    .filter(x -> "nDCG".equals(x.metricName()))
                    .mapToDouble(RetrievalMetrics.MetricResult::value)
                    .average().orElse(0.0);
            double avgFaith = rag.stream()
                    .filter(r -> r.getFaithfulnessResult() != null)
                    .mapToDouble(r -> r.getFaithfulnessResult().score())
                    .average().orElse(0.0);
            double avgHalluc = rag.stream()
                    .filter(r -> r.getHallucinationResult() != null)
                    .mapToDouble(r -> r.getHallucinationResult().hallucinationRate())
                    .average().orElse(0.0);

            m.put("rag.passRate", passRate);
            m.put("rag.avgComposite", avgComposite);
            m.put("rag.avgNdcg", avgNdcg);
            m.put("rag.avgFaithfulness", avgFaith);
            m.put("rag.avgHallucinationRate", avgHalluc);
        }

        if (agent == null || agent.isEmpty()) {
            m.put("agent.passRate", 0.0);
            m.put("agent.avgComposite", 0.0);
        } else {
            double agentPassRate = agent.stream().filter(AgentEvaluationResult::passed).count() / (double) agent.size();
            double agentAvgComposite = agent.stream().mapToDouble(AgentEvaluationResult::getCompositeScore).average().orElse(0.0);
            m.put("agent.passRate", agentPassRate);
            m.put("agent.avgComposite", agentAvgComposite);
        }

        return m;
    }

    /**
     * 执行门禁判定。
     *
     * @param rag      RAG 评测结果
     * @param agent    Agent 评测结果
     * @param cfg      门禁配置
     * @param baseline 已提交基线（可空；空时跳过回归比对）
     * @return 判定结果（{@link GateResult#passed()} 决定 CI 是否放行）
     */
    public GateResult evaluate(List<RAGEvaluationResult> rag,
                               List<AgentEvaluationResult> agent,
                               EvalGateConfig cfg,
                               EvalBaseline baseline) {
        Map<String, Double> metrics = collectMetrics(rag, agent);
        List<String> violations = new ArrayList<>();

        // ---- 1. 绝对阈值 ----
        double effRagPassThreshold = cfg.requireAllRagPass ? 1.0 : cfg.minRagPassRate;
        String ragPassLabel = cfg.requireAllRagPass
                ? "全部 RAG 用例须通过(requireAllRagPass)"
                : "RAG 通过率 >= " + cfg.minRagPassRate;
        check(metrics, "rag.passRate", effRagPassThreshold, true, ragPassLabel, violations);
        check(metrics, "rag.avgComposite", cfg.minAvgCompositeScore, true,
                "RAG 平均综合分 >= " + cfg.minAvgCompositeScore, violations);
        check(metrics, "rag.avgNdcg", cfg.minAvgNdcg, true,
                "RAG 平均 nDCG >= " + cfg.minAvgNdcg, violations);
        check(metrics, "rag.avgFaithfulness", cfg.minAvgFaithfulnessScore, true,
                "RAG 平均 Faithfulness >= " + cfg.minAvgFaithfulnessScore, violations);
        check(metrics, "rag.avgHallucinationRate", cfg.maxAvgHallucinationRate, false,
                "RAG 平均幻觉率 <= " + cfg.maxAvgHallucinationRate, violations);

        if (cfg.enableAgentGate) {
            check(metrics, "agent.passRate", cfg.minAgentPassRate, true,
                    "Agent 通过率 >= " + cfg.minAgentPassRate, violations);
        }

        // ---- 2. 基线回归 ----
        if (cfg.compareToBaseline && baseline != null && baseline.metrics != null) {
            for (Map.Entry<String, Double> e : baseline.metrics.entrySet()) {
                String key = e.getKey();
                Double base = e.getValue();
                Double cur = metrics.get(key);
                if (cur == null || !METRIC_DIRECTION.containsKey(key)) {
                    continue;
                }
                boolean higherBetter = METRIC_DIRECTION.get(key);
                double delta = cur - base;
                boolean regression = higherBetter ? (delta < -cfg.maxRegression) : (delta > cfg.maxRegression);
                if (regression) {
                    violations.add(String.format(
                            "基线回归: %s 由 %.4f 变为 %.4f (容忍 ±%.4f, 方向=%s)",
                            key, base, cur, cfg.maxRegression, higherBetter ? "越高越好" : "越低越好"));
                }
            }
        }

        return new GateResult(violations.isEmpty(), violations, metrics);
    }

    private void check(Map<String, Double> m, String key, double threshold,
                       boolean higherBetter, String label, List<String> violations) {
        Double v = m.get(key);
        if (v == null) {
            violations.add("指标缺失: " + key);
            return;
        }
        boolean ok = higherBetter ? (v >= threshold) : (v <= threshold);
        if (!ok) {
            violations.add(String.format("阈值未达: %s (当前=%.4f, 要求 %s %.4f)",
                    label, v, higherBetter ? ">=" : "<=", threshold));
        }
    }

    /**
     * 门禁判定结果。
     *
     * @param passed     是否通过（决定 CI 放行）
     * @param violations 违规明细（空列表表示通过）
     * @param metrics    本次指标快照
     */
    public record GateResult(boolean passed, List<String> violations, Map<String, Double> metrics) {
    }
}
