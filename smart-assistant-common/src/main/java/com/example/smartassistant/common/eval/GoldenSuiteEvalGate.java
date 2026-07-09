/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.example.smartassistant.common.rag.eval.RAGEvaluationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 黄金测试集评测闭环编排器 — 将「加载黄金集 → 跑 RAG/Agent 评测 → 门禁判定 → 报告/基线」串成一条离线可运行链路。
 *
 * <p>这是「评测闭环接入 CI」的核心入口：CI 仅需调用 {@link #run(String, String, Path, Path, boolean)}
 * 即可得到 {@link EvalGate.GateResult}，据此放行或阻断构建。整个过程纯内存计算，无需 Redis/PG。</p>
 *
 * <h3>Agent 评测增强（文章《Agent 评测体系》四缺口落地）</h3>
 * 当调用方注入真实 {@link TrialRunner.TrialExecutor} 运行器（即系统运行时能实际执行 Agent 拿到结果），
 * 且 {@link EvalGateConfig#enableAgentGate}=true 且 {@link EvalGateConfig#agentTrialCount}&gt;1 时，
 * 本编排器改用 {@link EvalPipeline} 跑完整增强评测：
 *
 * <pre>
 *   黄金用例 → [TrialRunner ×N] → [GraderWaterfall(规则→LLM)] →
 *   [pass@k / pass^k 聚合] → [HumanReviewRouter] → [RootCauseAnalyzer 聚类定责]
 * </pre>
 *
 * 并以 <b>pass^k（k 次全部通过的概率）</b> 作为单用例「稳定通过」的判定依据（而非单次运气），
 * 由 {@link AgentEvaluationResult.Builder#overridePassed(Boolean)} 注入门禁，复用既有 {@link EvalGate}
 * 的 {@code agent.passRate} 聚合与 {@code minAgentPassRate} 双重校验——<b>不改动 EvalGate</b>。
 *
 * <p>未注入运行器时（默认 CI 离线黄金集）Agent 用例仍生成「待执行」占位，仅作信息展示，行为向后兼容。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * // CI 门禁（不更新基线，离线占位）
 * GateResult r = new GoldenSuiteEvalGate().run(
 *     "/eval-test-suite.json", "/eval-gate-config.json",
 *     Path.of("target/eval-reports"), Path.of("src/test/resources/eval-baseline.json"), false);
 * if (!r.passed()) System.exit(1);
 *
 * // 注入真实运行器 → 启用 Trial×pass^k 稳定性门禁
 * GateResult r2 = new GoldenSuiteEvalGate().run(
 *     "/eval-test-suite.json", cfg, reportDir, baseline, false, myAgentExecutor);
 *
 * // 人工确认质量变化后重新生成基线
 * new GoldenSuiteEvalGate().run(..., true);
 * }</pre>
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
public class GoldenSuiteEvalGate {

    private static final Logger log = LoggerFactory.getLogger(GoldenSuiteEvalGate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 执行一次完整评测闭环（资源加载配置版，离线占位 Agent）。
     *
     * @see #run(String, EvalGateConfig, Path, Path, boolean, TrialRunner.TrialExecutor)
     */
    public EvalGate.GateResult run(String suiteResource, String configResource,
                                    Path reportDir, Path baselinePath, boolean updateBaseline) {
        EvalGateConfig cfg = loadConfig(configResource);
        return run(suiteResource, cfg, reportDir, baselinePath, updateBaseline, null);
    }

    /**
     * 标准质量门禁入口（P5-D 固化：Trial×pass^k 成为默认 Gate）。
     *
     * <p>注入真实 {@link TrialRunner.TrialExecutor} 时，自动启用硬化默认配置：
     * 启用 Agent 门禁、Trial 次数=5、稳定通过率阈值 pass^k≥0.8、绝对通过率≥0.8，
     * 不再要求调用方手工拼装 {@link EvalGateConfig}。未注入运行器时退化为离线占位（仅信息展示），
     * 行为与 {@link #run(String, String, Path, Path, boolean)} 一致、向后兼容。</p>
     *
     * @param suiteResource  黄金测试集 classpath 资源
     * @param reportDir      报告输出目录
     * @param agentExecutor  真实 Agent 运行器（可空）
     * @return 门禁判定结果
     */
    public EvalGate.GateResult run(String suiteResource, Path reportDir,
                                    TrialRunner.TrialExecutor agentExecutor) {
        return run(suiteResource, defaultHardenedConfig(), reportDir, null, false, agentExecutor);
    }

    /**
     * 硬化默认门禁配置：把 Trial×pass^k 稳定性门禁设为标准放行条件。
     */
    private static EvalGateConfig defaultHardenedConfig() {
        EvalGateConfig cfg = new EvalGateConfig();
        cfg.minRagPassRate = 1.0;
        cfg.requireAllRagPass = true;
        cfg.minAvgCompositeScore = 0.7;
        cfg.maxAvgHallucinationRate = 0.15;
        cfg.minAvgFaithfulnessScore = 0.7;
        cfg.minAvgNdcg = 0.5;
        cfg.enableAgentGate = true;       // ⭐ 固化：默认启用 Agent 稳定性门禁
        cfg.agentTrialCount = 5;          // ⭐ Trial×5
        cfg.minAgentPassKRate = 0.8;      // ⭐ pass^k≥0.8 才算稳定
        cfg.minAgentPassRate = 0.8;
        cfg.compareToBaseline = false;    // 默认不比对基线（避免缺失基线文件阻断）
        cfg.maxRegression = 0.05;
        return cfg;
    }

    /**
     * 执行一次完整评测闭环（配置对象版，可注入 Agent 运行器）。
     *
     * @param suiteResource   黄金测试集 classpath 资源（如 {@code /eval-test-suite.json}）
     * @param cfg             门禁配置对象（可直接构造，便于测试与运行时注入）
     * @param reportDir       报告输出目录（自动创建）
     * @param baselinePath    基线文件路径（可空；存在则参与回归比对）
     * @param updateBaseline  为 true 且 baselinePath 非空时，运行结束后用本次指标覆盖基线
     * @param agentExecutor   真实 Agent 运行器（可空；非空且 {@code agentTrialCount>1} 时启用 Trial×pass^k）
     * @return 门禁判定结果
     */
    public EvalGate.GateResult run(String suiteResource, EvalGateConfig cfg,
                                    Path reportDir, Path baselinePath, boolean updateBaseline,
                                    TrialRunner.TrialExecutor agentExecutor) {
        // 0. 参数防御
        if (cfg == null) {
            cfg = new EvalGateConfig();
        }

        // 1. 加载并运行黄金集评测
        EvaluationReportService svc = new EvaluationReportService();
        svc.loadTestSuite(suiteResource);
        List<RAGEvaluationResult> rag = svc.runRAGEvaluationDetailed();

        // Agent 评测：注入运行器且启用 Trial 时走增强管线；否则离线占位
        boolean trialEnabled = agentExecutor != null && cfg.enableAgentGate && cfg.agentTrialCount > 1;
        List<AgentEvaluationResult> agent;
        if (trialEnabled) {
            agent = runAgentTrials(svc, cfg, agentExecutor);
        } else {
            // 离线模式下 Agent 用例无 live 运行器，生成"待执行"占位（仅信息展示）
            agent = svc.runAgentEvaluationDetailed(null);
        }

        // 2. 加载基线
        EvalBaseline baseline = (baselinePath != null) ? EvalBaseline.load(baselinePath) : null;
        if (baseline == null && cfg.compareToBaseline) {
            log.info("[EvalGate] 未找到基线文件（{}），本次仅做绝对阈值校验", baselinePath);
        }

        // 3. 门禁判定（复用既有 EvalGate，Agent 通过率由 overridePassed 携带 pass^k 结论）
        EvalGate gate = new EvalGate();
        EvalGate.GateResult result = gate.evaluate(rag, agent, cfg, baseline);

        // 4. 报告导出
        int ragPassed = (int) rag.stream().filter(RAGEvaluationResult::passed).count();
        try {
            EvalReportExporter.write(reportDir, result, rag.size(), ragPassed, agent.size());
            log.info("[EvalGate] 报告已写出: {}", reportDir);
        } catch (Exception e) {
            log.warn("[EvalGate] 报告写出失败（不影响判定）: {}", e.getMessage());
        }

        // 5. 可选更新基线
        if (updateBaseline && baselinePath != null) {
            try {
                EvalBaseline nb = new EvalBaseline();
                nb.generatedAt = Instant.now().toString();
                nb.note = "SmartAssistant 评测基线快照（人工确认后由 GoldenSuiteEvalGate 生成）";
                nb.metrics = result.metrics();
                nb.save(baselinePath);
            } catch (Exception e) {
                log.warn("[EvalGate] 基线更新失败: {}", e.getMessage());
            }
        }

        if (result.passed()) {
            log.info("[EvalGate] ✅ 门禁通过（RAG {}/{} 通过{}）", ragPassed, rag.size(),
                    trialEnabled ? "，Agent 已启用 Trial×pass^k" : "");
        } else {
            log.error("[EvalGate] ❌ 门禁未通过: {}", String.join("; ", result.violations()));
        }
        return result;
    }

    /**
     * 通过 {@link EvalPipeline} 对全部 Agent 用例跑 Trial×pass^k，并把 pass^k 结论映射回
     * {@link AgentEvaluationResult}（overridePassed），供既有 {@link EvalGate} 聚合门禁。
     */
    private List<AgentEvaluationResult> runAgentTrials(EvaluationReportService svc, EvalGateConfig cfg,
                                                       TrialRunner.TrialExecutor agentExecutor) {
        log.info("[EvalGate] 启用 Agent Trial×pass^k：trials={}, minPassK={}",
                cfg.agentTrialCount, cfg.minAgentPassKRate);

        EvalPipeline pipeline = new EvalPipeline.Builder()
                .trials(cfg.agentTrialCount)
                .k(cfg.agentTrialCount)
                .executor(agentExecutor)
                .build();

        List<EvalPipeline.CaseVerdict> verdicts = pipeline.evaluateAll(svc.getAgentTestCases());

        List<AgentEvaluationResult> out = new ArrayList<>(verdicts.size());
        int stableCount = 0;
        for (EvalPipeline.CaseVerdict v : verdicts) {
            boolean stable = v.passK().passPowerK() >= cfg.minAgentPassKRate;
            if (stable) stableCount++;

            AgentEvaluationResult rep = representativeTrial(v);
            AgentEvaluationResult r = new AgentEvaluationResult.Builder(v.caseId())
                    .caseName(rep.getCaseName())
                    .agentName(rep.getAgentName())
                    .input(rep.getInput())
                    .expectedIntent(rep.getExpectedIntent())
                    .expectedTools(rep.getExpectedTools())
                    .expectedKeywords(rep.getExpectedKeywords())
                    .actualResponse(rep.getActualResponse())
                    .actualIntent(rep.getActualIntent())
                    .actualToolsCalled(rep.getActualToolsCalled())
                    .actualToolCallCount(rep.getActualToolCallCount())
                    .actualIterations(rep.getActualIterations())
                    .actualLatencyMs(rep.getActualLatencyMs())
                    .totalTokens(rep.getTotalTokens())
                    .hasError(rep.isHasError())
                    .errorMessage(rep.getErrorMessage())
                    .notes(String.format("pass^k=%.3f (gate@%.2f) | %s",
                            v.passK().passPowerK(), cfg.minAgentPassKRate, v.passK()))
                    .overridePassed(stable)
                    .build();
            out.add(r);
            log.info("[EvalGate] Agent[{}] {} | {}", v.caseId(), stable ? "稳定✅" : "不稳定❌", v.passK());
        }

        double avgPassK = verdicts.stream().mapToDouble(v -> v.passK().passPowerK()).average().orElse(0.0);
        log.info("[EvalGate] Agent Trial×pass^k 汇总：{}/{} 用例稳定通过，平均 pass^k={}",
                stableCount, verdicts.size(), String.format("%.3f", avgPassK));

        // 根因分析（文章 §10：聚类定责）
        RootCauseAnalysis rca = pipeline.lastRootCause();
        if (rca != null && rca.diagnoses() != null && !rca.diagnoses().isEmpty()) {
            log.info("[EvalGate] 根因分析：{} 个聚类", rca.diagnoses().size());
            rca.diagnoses().forEach(d -> log.info("  - [{}] {} | 责任:{} | 修复:{}",
                    d.type(), d.rootCause(), d.responsibleRole(), d.recommendation()));
        }
        return out;
    }

    /** 从多轮 Trial 中选代表性结果（复合分最高者，平局取首个）。 */
    private static AgentEvaluationResult representativeTrial(EvalPipeline.CaseVerdict v) {
        List<AgentEvaluationResult> trials = v.trial().trials();
        if (trials.isEmpty()) {
            // 极端情况：运行器无有效输出，构造一个失败占位
            return new AgentEvaluationResult.Builder(v.caseId())
                    .actualResponse("[Trial 无有效输出]").hasError(true)
                    .errorMessage("TrialExecutor 未返回有效 AgentEvaluationResult").build();
        }
        return trials.stream()
                .max(java.util.Comparator.comparingDouble(AgentEvaluationResult::getCompositeScore))
                .orElse(trials.get(0));
    }

    private EvalGateConfig loadConfig(String resource) {
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            if (is != null) {
                return MAPPER.readValue(is, EvalGateConfig.class);
            }
        } catch (Exception e) {
            log.warn("[EvalGate] 配置加载失败（使用默认配置）: {}", e.getMessage());
        }
        return new EvalGateConfig();
    }

    /**
     * 独立运行入口：{@code java GoldenSuiteEvalGate <suite> <config> <reportDir> <baselinePath> [updateBaseline=true]}.
     * 主要用于人工重新生成基线（离线占位模式，不注入运行器）。
     */
    public static void main(String[] args) throws Exception {
        String suite = args.length > 0 ? args[0] : "/eval-test-suite.json";
        String config = args.length > 1 ? args[1] : "/eval-gate-config.json";
        Path reportDir = Path.of(args.length > 2 ? args[2] : "target/eval-reports");
        Path baseline = Path.of(args.length > 3 ? args[3] : "src/test/resources/eval-baseline.json");
        boolean update = args.length > 4 && Boolean.parseBoolean(args[4]);

        EvalGate.GateResult r = new GoldenSuiteEvalGate().run(suite, config, reportDir, baseline, update);
        System.out.println("=== EvalGate 结论: " + (r.passed() ? "通过" : "未通过") + " ===");
        r.metrics().forEach((k, v) -> System.out.printf("  %s = %.4f%n", k, v));
        if (!r.violations().isEmpty()) {
            System.out.println("违规:");
            r.violations().forEach(v -> System.out.println("  - " + v));
        }
        System.exit(r.passed() ? 0 : 1);
    }
}
