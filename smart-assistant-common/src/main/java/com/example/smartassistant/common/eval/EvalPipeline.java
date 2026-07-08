/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.example.smartassistant.common.eval.grader.Grader;
import com.example.smartassistant.common.eval.grader.GraderResult;
import com.example.smartassistant.common.eval.grader.GraderWaterfall;
import com.example.smartassistant.common.eval.grader.LlmGrader;
import com.example.smartassistant.common.eval.grader.RuleGrader;
import com.example.smartassistant.common.eval.EvaluationReportService.AgentTestSpec;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 评测增强管线 — 将文章《Agent 评测体系》四大缺口的落地组件串成可运行链路：
 *
 * <pre>
 *   黄金用例 → [① TrialRunner ×N] → [② GraderWaterfall(规则→LLM)] →
 *   [① pass@k / pass^k 聚合] → [③ 边界/低置信 → HumanReviewRouter] →
 *   [④ RootCauseAnalyzer 聚类定责 + 修复工单]
 * </pre>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li><b>离线安全</b>：未注入 {@link ChatModel} 时仅用 RuleGrader（纯内存，CI 友好）；</li>
 *   <li><b>解耦</b>：Agent 实际执行通过 {@code TrialRunner.TrialExecutor} 注入，评测框架不依赖运行时；</li>
 *   <li><b>可观测</b>：每一步产出结构化结果，便于报告与 Grafana 采集。</li>
 * </ul>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public class EvalPipeline {

    private final int trials;
    private final int k;
    private final TrialRunner.TrialExecutor executor;
    private final GraderWaterfall waterfall;
    private final HumanReviewRouter humanRouter;
    private final RootCauseAnalyzer rootCauseAnalyzer;
    private RootCauseAnalysis lastRootCause;

    private EvalPipeline(Builder b) {
        this.trials = b.trials;
        this.k = b.k;
        this.executor = b.executor;
        this.humanRouter = b.humanRouter;
        this.rootCauseAnalyzer = b.rootCauseAnalyzer != null ? b.rootCauseAnalyzer : new RootCauseAnalyzer();

        // 构建瀑布流：规则 + (可选 LLM)
        List<Grader> graders = new ArrayList<>();
        graders.add(new RuleGrader());
        if (b.chatModel != null) {
            graders.add(new LlmGrader(b.chatModel));
        }
        this.waterfall = new GraderWaterfall(graders);
    }

    /** 对单个用例跑完整增强评测。 */
    public CaseVerdict evaluate(AgentTestSpec spec) {
        TrialResult trial = new TrialRunner().run(spec, trials, executor);
        PassKCalculator.PassKResult passK = PassKCalculator.from(trial, k);

        List<GraderResult> graderResults = new ArrayList<>();
        for (AgentEvaluationResult r : trial.trials()) {
            GraderResult gr = waterfall.grade(r, spec);
            graderResults.add(gr);
            // 边界 / 低置信 / 2% 抽检 → 人工路由（编排器统一负责，避免与瀑布流重复）
            humanRouter.route(r, gr, spec);
        }
        double avgScore = graderResults.stream().mapToDouble(GraderResult::score).average().orElse(0);

        return new CaseVerdict(spec.id, trial, passK, avgScore, graderResults);
    }

    /** 对一组用例批量评测，并汇总全量失败做根因分析。 */
    public List<CaseVerdict> evaluateAll(List<AgentTestSpec> specs) {
        List<CaseVerdict> out = new ArrayList<>();
        List<AgentEvaluationResult> allFailures = new ArrayList<>();
        for (AgentTestSpec s : specs) {
            CaseVerdict v = evaluate(s);
            out.add(v);
            v.trial().trials().stream().filter(r -> !r.passed()).forEach(allFailures::add);
        }
        this.lastRootCause = rootCauseAnalyzer.analyze(allFailures);
        return out;
    }

    public RootCauseAnalysis lastRootCause() {
        return lastRootCause;
    }

    /** 单用例综合判定。 */
    public record CaseVerdict(String caseId, TrialResult trial, PassKCalculator.PassKResult passK,
                              double avgGraderScore, List<GraderResult> graderResults) {
        public String summary() {
            return String.format("[%s] Trial n=%d pass=%d | %s | 平均分=%.2f",
                    caseId, trial.trialCount(), trial.passCount(), passK, avgGraderScore);
        }
    }

    // ==================== Builder ====================

    public static class Builder {
        private int trials = 5;
        private int k = 5;
        private TrialRunner.TrialExecutor executor;
        private ChatModel chatModel;
        private HumanReviewRouter humanRouter;
        private RootCauseAnalyzer rootCauseAnalyzer;

        public Builder trials(int v) {
            this.trials = v;
            return this;
        }

        public Builder k(int v) {
            this.k = v;
            return this;
        }

        public Builder executor(TrialRunner.TrialExecutor v) {
            this.executor = v;
            return this;
        }

        public Builder chatModel(ChatModel v) {
            this.chatModel = v;
            return this;
        }

        public Builder humanRouter(HumanReviewRouter v) {
            this.humanRouter = v;
            return this;
        }

        public Builder rootCauseAnalyzer(RootCauseAnalyzer v) {
            this.rootCauseAnalyzer = v;
            return this;
        }

        public EvalPipeline build() {
            Objects.requireNonNull(executor, "TrialExecutor 必填");
            if (humanRouter == null) {
                humanRouter = new HumanReviewRouter(new InMemoryHumanReviewStore());
            }
            return new EvalPipeline(this);
        }
    }
}
