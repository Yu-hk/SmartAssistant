/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ⭐ 检索失败样本分析服务 — 线上答错时追查完整检索链路。
 * <p>
 * 使用 {@link RetrievalTraceRepository} 获取追踪记录，分析失败原因：
 * <ul>
 *   <li><b>Query 改写问题</b>：原始 query 与改写后的 query variants 差异过大</li>
 *   <li><b>召回遗漏</b>：各路召回均未命中期望文档</li>
 *   <li><b>排序问题</b>：期望文档虽然被召回但排在很后面</li>
 * </ul>
 * </p>
 */
public class RetrievalFailureAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RetrievalFailureAnalyzer.class);

    private final RetrievalTraceRepository traceRepository;

    public RetrievalFailureAnalyzer(RetrievalTraceRepository traceRepository) {
        this.traceRepository = traceRepository;
    }

    /**
     * 分析指定请求的检索失败原因。
     *
     * @param requestId      请求 ID
     * @param expectedDocIds 期望命中的文档 ID（可选）
     * @return 分析结果
     */
    public AnalysisResult analyze(String requestId, List<String> expectedDocIds) {
        RetrievalTrace trace = traceRepository.findByRequestId(requestId);
        if (trace == null) {
            return new AnalysisResult(requestId, false, "未找到检索追溯记录（可能检索未执行或已过期）");
        }

        // 检查是否命中
        if (trace.isHit()) {
            return new AnalysisResult(requestId, true, "检索成功，未发现问题");
        }

        StringBuilder diagnosis = new StringBuilder();

        // 1. 检查 query variants 是否合理
        if (trace.getQueryVariants().size() > 1) {
            diagnosis.append("- 使用了 Multi-Query 扩展: ").append(trace.getQueryVariants()).append("\n");
        }

        // 2. 检查各路召回结果
        if (trace.getSteps().isEmpty()) {
            diagnosis.append("- ⚠️ 所有检索路径均未返回结果\n");
        } else {
            // 按 pathName 分组统计
            var byPath = trace.getSteps().stream()
                    .collect(Collectors.groupingBy(RetrievalTrace.RetrievalStep::pathName));
            diagnosis.append("- 各路径召回数: ");
            byPath.forEach((path, steps) -> diagnosis.append(path).append("=").append(steps.size()).append(" "));
            diagnosis.append("\n");

            // 检查期望文档是否被召回（如果提供了 expectedDocIds）
            if (expectedDocIds != null && !expectedDocIds.isEmpty()) {
                List<String> retrievedIds = trace.getSteps().stream()
                        .map(RetrievalTrace.RetrievalStep::docId)
                        .distinct()
                        .toList();
                List<String> notFound = expectedDocIds.stream()
                        .filter(id -> !retrievedIds.contains(id))
                        .toList();
                if (!notFound.isEmpty()) {
                    diagnosis.append("- ❌ 期望文档未召回: ").append(notFound).append("\n");
                }
            }

            // 检查 RRF 融合后结果
            if (trace.getFusedResults().isEmpty()) {
                diagnosis.append("- ⚠️ RRF 融合后无结果（所有路径分数过低）\n");
            } else {
                diagnosis.append("- RRF 融合 Top-3: ");
                trace.getFusedResults().stream().limit(3).forEach(f ->
                        diagnosis.append(f.docId()).append("(").append(String.format("%.4f", f.rrfScore())).append(") "));
                diagnosis.append("\n");
            }
        }

        // 3. 检查最终入 Prompt 的上下文
        if (trace.getFinalContext().isEmpty()) {
            diagnosis.append("- ⚠️ 最终未向 LLM 提供任何上下文\n");
        } else {
            diagnosis.append("- 最终入 Prompt: ").append(trace.getFinalContext().size()).append(" 段\n");
        }

        // 4. 性能
        diagnosis.append("- 总耗时: ").append(trace.getTotalDurationMs()).append("ms\n");

        return new AnalysisResult(requestId, false, diagnosis.toString().trim());
    }

    /**
     * 分析结果。
     *
     * @param requestId 请求 ID
     * @param success   检索是否成功
     * @param diagnosis 诊断信息
     */
    public record AnalysisResult(String requestId, boolean success, String diagnosis) {}
}
