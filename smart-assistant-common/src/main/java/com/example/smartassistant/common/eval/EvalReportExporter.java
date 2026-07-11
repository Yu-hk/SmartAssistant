/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 评测门禁报告导出器 — 将 {@link EvalGate.GateResult} 渲染为 Markdown 报告，
 * 并写出指标快照 JSON（供 CI 产物归档与人工审查）。
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
public final class EvalReportExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private EvalReportExporter() {
    }

    /**
     * 渲染 Markdown 报告。
     */
    public static String toMarkdown(EvalGate.GateResult result,
                                     int ragTotal, int ragPassed,
                                     int agentTotal) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG / Agent 评测门禁报告\n\n");
        sb.append("**结论**: ").append(result.passed() ? "✅ 通过（CI 放行）" : "❌ 未通过（CI 阻断）").append("\n\n");
        sb.append("- RAG 用例: **").append(ragPassed).append("** 通过 / ").append(ragTotal).append(" 总计\n");
        sb.append("- Agent 用例: ").append(agentTotal).append(" 总计（").append(result.passed() ? "信息展示" : "信息展示").append("）\n\n");

        sb.append("## 指标快照\n\n");
        sb.append("| 指标 | 当前值 |\n|---|---|\n");
        for (var e : result.metrics().entrySet()) {
            sb.append("| `").append(e.getKey()).append("` | ").append(String.format("%.4f", e.getValue())).append(" |\n");
        }

        if (!result.violations().isEmpty()) {
            sb.append("\n## 违规项（").append(result.violations().size()).append("）\n\n");
            for (String v : result.violations()) {
                sb.append("- ❌ ").append(v).append("\n");
            }
        } else {
            sb.append("\n## 违规项\n\n- 无 — 全部通过 ✅\n");
        }

        return sb.toString();
    }

    /**
     * 写出报告到目录：{dir}/eval-gate-report.md + {dir}/eval-gate-snapshot.json。
     *
     * @param dir        输出目录（自动创建）
     * @param result     门禁结果
     * @param ragTotal   RAG 用例总数
     * @param ragPassed  RAG 通过数
     * @param agentTotal Agent 用例总数
     */
    public static void write(Path dir, EvalGate.GateResult result,
                             int ragTotal, int ragPassed, int agentTotal) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("eval-gate-report.md"),
                toMarkdown(result, ragTotal, ragPassed, agentTotal));
        MAPPER.writeValue(dir.resolve("eval-gate-snapshot.json").toFile(), result.metrics());
    }
}
