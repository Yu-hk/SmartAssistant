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
import java.util.List;

/**
 * 黄金测试集评测闭环编排器 — 将「加载黄金集 → 跑 RAG/Agent 评测 → 门禁判定 → 报告/基线」串成一条离线可运行链路。
 *
 * <p>这是「评测闭环接入 CI」的核心入口：CI 仅需调用 {@link #run(String, String, Path, Path, boolean)}
 * 即可得到 {@link EvalGate.GateResult}，据此放行或阻断构建。整个过程纯内存计算，无需 Redis/PG。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * // CI 门禁（不更新基线）
 * GateResult r = new GoldenSuiteEvalGate().run(
 *     "/eval-test-suite.json", "/eval-gate-config.json",
 *     Path.of("target/eval-reports"), Path.of("src/test/resources/eval-baseline.json"), false);
 * if (!r.passed()) System.exit(1);
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
     * 执行一次完整评测闭环。
     *
     * @param suiteResource   黄金测试集 classpath 资源（如 {@code /eval-test-suite.json}）
     * @param configResource  门禁配置 classpath 资源（如 {@code /eval-gate-config.json}）
     * @param reportDir       报告输出目录（自动创建）
     * @param baselinePath    基线文件路径（可空；存在则参与回归比对）
     * @param updateBaseline  为 true 且 baselinePath 非空时，运行结束后用本次指标覆盖基线
     * @return 门禁判定结果
     */
    public EvalGate.GateResult run(String suiteResource, String configResource,
                                    Path reportDir, Path baselinePath, boolean updateBaseline) {
        // 1. 加载并运行黄金集评测
        EvaluationReportService svc = new EvaluationReportService();
        svc.loadTestSuite(suiteResource);
        List<RAGEvaluationResult> rag = svc.runRAGEvaluationDetailed();
        // 离线模式下 Agent 用例无 live 运行器，生成"待执行"占位（仅信息展示）
        java.util.List<AgentEvaluationResult> agent = svc.runAgentEvaluationDetailed(null);

        // 2. 加载门禁配置 + 基线
        EvalGateConfig cfg = loadConfig(configResource);
        EvalBaseline baseline = (baselinePath != null) ? EvalBaseline.load(baselinePath) : null;
        if (baseline == null && cfg.compareToBaseline) {
            log.info("[EvalGate] 未找到基线文件（{}），本次仅做绝对阈值校验", baselinePath);
        }

        // 3. 门禁判定
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
            log.info("[EvalGate] ✅ 门禁通过（RAG {}/{} 通过）", ragPassed, rag.size());
        } else {
            log.error("[EvalGate] ❌ 门禁未通过: {}", String.join("; ", result.violations()));
        }
        return result;
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
     * 主要用于人工重新生成基线。
     */
    public static void main(String[] args) throws Exception {
        String suite = args.length > 0 ? args[0] : "/eval-test-suite.json";
        String config = args.length > 1 ? args[1] : "/eval-gate-config.json";
        Path reportDir = Path.of(args.length > 2 ? args[2] : "target/eval-reports");
        Path baseline = Path.of(args.length > 3 ? args[3] : "src/test/resources/eval-baseline.json");
        boolean update = args.length > 4 && Boolean.parseBoolean(args[4]);

        EvalGate.GateResult r = new GoldenSuiteEvalGate().run(suite, config, reportDir, baseline, update);
        System.out.println("=== EvalGate 结论: " + (r.passed() ? "通过" : "未通过") + " ===");
        System.out.println("指标:");
        r.metrics().forEach((k, v) -> System.out.printf("  %s = %.4f%n", k, v));
        if (!r.violations().isEmpty()) {
            System.out.println("违规:");
            r.violations().forEach(v -> System.out.println("  - " + v));
        }
        System.exit(r.passed() ? 0 : 1);
    }
}
