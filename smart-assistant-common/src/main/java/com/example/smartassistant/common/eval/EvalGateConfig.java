/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 评测闭环质量门禁配置 — 定义 RAG/Agent 黄金测试集在 CI 中必须满足的最低质量阈值，
 * 以及相对基线的回归容忍度。
 *
 * <p>由 {@code eval-gate-config.json}（classpath / test-resources）反序列化加载；
 * 无配置文件时由构造器提供生产默认值。</p>
 *
 * <p>指标方向约定（与 {@link EvalGate} 中的 {@code METRIC_DIRECTION} 一致）：</p>
 * <ul>
 *   <li>越高越好：rag.passRate / rag.avgComposite / rag.avgNdcg / rag.avgFaithfulness / agent.*</li>
 *   <li>越低越好：rag.avgHallucinationRate</li>
 * </ul>
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalGateConfig {

    /** RAG 用例通过率下限（0~1）。默认 1.0 表示全部黄金 RAG 用例必须通过。 */
    public double minRagPassRate = 1.0;

    /** 是否要求每一个 RAG 用例都通过（任一失败即门禁失败）。优先级高于 {@link #minRagPassRate}。 */
    public boolean requireAllRagPass = true;

    /** RAG 平均综合评分下限（0~1）。 */
    public double minAvgCompositeScore = 0.7;

    /** RAG 平均幻觉率上限（0~1）。 */
    public double maxAvgHallucinationRate = 0.15;

    /** RAG 平均 Faithfulness 评分下限（0~1）。 */
    public double minAvgFaithfulnessScore = 0.7;

    /** RAG 平均 nDCG 下限（0~1）。 */
    public double minAvgNdcg = 0.5;

    /**
     * 是否启用 Agent 评测门禁。
     * <p>默认关闭：Agent 用例需要 live 运行器注入实际执行结果才有意义，
     * 离线黄金集仅能生成"待执行"占位，故默认仅作信息展示、不计入门禁。</p>
     */
    public boolean enableAgentGate = false;

    /** Agent 用例通过率下限（0~1，仅 {@link #enableAgentGate}=true 时生效）。 */
    public double minAgentPassRate = 0.8;

    /** 是否开启相对基线回归检测（需存在已提交基线 {@link EvalBaseline}）。 */
    public boolean compareToBaseline = true;

    /**
     * 回归容忍度（绝对值）。
     * <p>当前指标相对基线变动超过该值即判定为回归（越高越好的指标下降、或越低越好的指标上升）。</p>
     */
    public double maxRegression = 0.05;

    /** 默认构造器（生产默认值）。 */
    public EvalGateConfig() {
    }
}
