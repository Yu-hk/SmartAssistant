/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测闭环 CI 门禁测试 — 这是「评测闭环接入 CI」的核心质量卡点。
 *
 * <p>行为：</p>
 * <ul>
 *   <li>运行 {@link GoldenSuiteEvalGate} 对黄金测试集（{@code /eval-test-suite.json}）执行离线评测 + 门禁判定；</li>
 *   <li>若 {@code src/test/resources/eval-baseline.json} 不存在（首次），则自举生成基线（仅做绝对阈值校验）；</li>
 *   <li>若基线已存在（CI/后续运行），则与基线比对，任何指标回归超过容忍度即失败；</li>
 *   <li>无论结果如何，报告写到 {@code target/eval-reports/}（CI 作为产物归档）。</li>
 * </ul>
 *
 * <p>该测试纯内存计算，无需 Redis/PG，可在任意 CI Runner 确定性运行。</p>
 */
class GoldenSuiteEvalGateTest {

    @Test
    void goldenSuiteEvalGateMustPass() {
        Path reportDir = Paths.get("target/eval-reports");
        Path baselinePath = Paths.get("src/test/resources/eval-baseline.json");

        // 首次无基线时自举生成（不比对回归），后续运行比对基线
        boolean updateBaseline = !Files.exists(baselinePath);

        EvalGate.GateResult result = new GoldenSuiteEvalGate().run(
                "/eval-test-suite.json",
                "/eval-gate-config.json",
                reportDir,
                baselinePath,
                updateBaseline);

        // 控制台输出便于本地/CI 调试
        System.out.println("=== EvalGate 结论: " + (result.passed() ? "通过" : "未通过") + " ===");
        result.metrics().forEach((k, v) -> System.out.printf("  %s = %.4f%n", k, v));
        if (!result.violations().isEmpty()) {
            System.out.println("违规项:");
            result.violations().forEach(v -> System.out.println("  - " + v));
        }

        assertTrue(result.passed(),
                "评测门禁未通过，违规项: " + String.join("; ", result.violations()));
    }
}
