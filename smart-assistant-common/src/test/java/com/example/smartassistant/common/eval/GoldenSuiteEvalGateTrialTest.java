/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link GoldenSuiteEvalGate} 接入 {@link EvalPipeline} 后的 Trial×pass^k 稳定性门禁：
 * <ul>
 *   <li>注入「始终通过」运行器 + agentTrialCount=5 → 全部用例 pass^k=1.0 → 门禁通过；</li>
 *   <li>注入「5 次中仅 3 次通过」运行器 → pass^k=(0.6)^5≈0.078 < 0.8 → 全部不稳定 → 门禁失败。</li>
 * </ul>
 * 两个用例均复用既有 {@link EvalGate}，仅通过 {@code overridePassed} 注入 pass^k 结论，
 * 验证「不改动 EvalGate 即可升级为稳定性门禁」。
 */
class GoldenSuiteEvalGateTrialTest {

    /** 运行器：每次都返回自然通过的 AgentEvaluationResult。 */
    private static TrialRunner.TrialExecutor alwaysPass() {
        return spec -> new AgentEvaluationResult.Builder(spec.id)
                .caseName(spec.caseName)
                .agentName(spec.agentName)
                .input(spec.input)
                .expectedIntent(spec.expectedIntent)
                .expectedTools(spec.expectedTools)
                .expectedKeywords(spec.expectedKeywords)
                .actualIntent(spec.expectedIntent)
                .actualResponse("已满足预期关键词：" + String.join("、", spec.expectedKeywords))
                .actualToolsCalled(spec.expectedTools)
                .build();
    }

    /** 运行器：每 5 次运行中恰好前 3 次通过、后 2 次失败（确定性，便于断言 pass^k）。 */
    private static TrialRunner.TrialExecutor passThreeOfFive() {
        AtomicInteger counter = new AtomicInteger(0);
        return spec -> {
            boolean pass = (counter.getAndIncrement() % 5) < 3;
            if (pass) {
                return new AgentEvaluationResult.Builder(spec.id)
                        .caseName(spec.caseName).agentName(spec.agentName).input(spec.input)
                        .expectedIntent(spec.expectedIntent).expectedTools(spec.expectedTools)
                        .expectedKeywords(spec.expectedKeywords)
                        .actualIntent(spec.expectedIntent)
                        .actualResponse("已满足预期关键词：" + String.join("、", spec.expectedKeywords))
                        .actualToolsCalled(spec.expectedTools)
                        .build();
            }
            return new AgentEvaluationResult.Builder(spec.id)
                    .expectedIntent(spec.expectedIntent)
                    .expectedKeywords(spec.expectedKeywords)
                    .actualResponse("未能完成")
                    .actualToolsCalled(List.of())
                    .hasError(true)
                    .build();
        };
    }

    private static EvalGateConfig trialConfig() {
        EvalGateConfig cfg = new EvalGateConfig();
        cfg.enableAgentGate = true;
        cfg.agentTrialCount = 5;
        cfg.minAgentPassKRate = 0.8;
        cfg.minAgentPassRate = 0.8;
        cfg.compareToBaseline = false; // 本测试聚焦 Trial×pass^k，不比对基线
        return cfg;
    }

    @Test
    void trialAllPassGatePasses() {
        EvalGateConfig cfg = trialConfig();
        EvalGate.GateResult result = new GoldenSuiteEvalGate().run(
                "/eval-test-suite.json", cfg,
                Paths.get("target/eval-reports-trial"), null, false, alwaysPass());

        System.out.println("[TrialTest] alwaysPass → " + result.metrics());
        assertTrue(result.passed(),
                "始终通过的运行器 pass^k=1.0，门禁应放行；违规: " + String.join("; ", result.violations()));
    }

    @Test
    void trialThreeOfFiveGateFails() {
        EvalGateConfig cfg = trialConfig();
        EvalGate.GateResult result = new GoldenSuiteEvalGate().run(
                "/eval-test-suite.json", cfg,
                Paths.get("target/eval-reports-trial-flaky"), null, false, passThreeOfFive());

        System.out.println("[TrialTest] 3/5 → " + result.metrics());
        assertFalse(result.passed(),
                "5 次仅 3 次通过的运行器 pass^k=(0.6)^5≈0.078<0.8，门禁应阻断");
    }

    @Test
    void withoutExecutorFallsBackToPlaceholder() {
        // 不注入运行器 → 走离线占位分支，enableAgentGate=false 时不计入门禁（仅信息）
        EvalGateConfig cfg = new EvalGateConfig(); // enableAgentGate 默认 false
        EvalGate.GateResult result = new GoldenSuiteEvalGate().run(
                "/eval-test-suite.json", cfg,
                Paths.get("target/eval-reports-placeholder"), null, false, null);

        System.out.println("[TrialTest] placeholder → " + result.metrics());
        assertTrue(result.passed(), "离线占位模式（未注入运行器）门禁应放行（仅信息展示）");
    }
}
