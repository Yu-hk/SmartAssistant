/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 P5-D 固化的标准门禁入口 {@link GoldenSuiteEvalGate#run(String, Path, TrialRunner.TrialExecutor)}：
 * 注入运行器时自动启用硬化默认配置（Trial×5、pass^k≥0.8），无需调用方手工拼装 {@link EvalGateConfig}。
 */
class GoldenSuiteEvalGateDefaultTest {

    private static TrialRunner.TrialExecutor alwaysPass() {
        return spec -> new AgentEvaluationResult.Builder(spec.id)
                .caseName(spec.caseName).agentName(spec.agentName).input(spec.input)
                .expectedIntent(spec.expectedIntent).expectedTools(spec.expectedTools)
                .expectedKeywords(spec.expectedKeywords)
                .actualIntent(spec.expectedIntent)
                .actualResponse("已满足预期关键词：" + String.join("、", spec.expectedKeywords))
                .actualToolsCalled(spec.expectedTools)
                .build();
    }

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
                    .expectedIntent(spec.expectedIntent).expectedKeywords(spec.expectedKeywords)
                    .actualResponse("未能完成").actualToolsCalled(List.of()).hasError(true).build();
        };
    }

    @Test
    void defaultGateAlwaysPassPasses() {
        EvalGate.GateResult result = new GoldenSuiteEvalGate().run(
                "/eval-test-suite.json",
                Paths.get("target/eval-reports-default-pass"),
                alwaysPass());
        System.out.println("[DefaultGate] alwaysPass → " + result.metrics());
        assertTrue(result.passed(),
                "固化默认门禁（Trial×pass^k）应在运行器全过时放行；违规: " + String.join("; ", result.violations()));
    }

    @Test
    void defaultGateFlakyFails() {
        EvalGate.GateResult result = new GoldenSuiteEvalGate().run(
                "/eval-test-suite.json",
                Paths.get("target/eval-reports-default-flaky"),
                passThreeOfFive());
        System.out.println("[DefaultGate] 3/5 → " + result.metrics());
        assertFalse(result.passed(),
                "固化默认门禁应在 5 次仅 3 次通过（pass^k≈0.078<0.8）时阻断");
    }
}
