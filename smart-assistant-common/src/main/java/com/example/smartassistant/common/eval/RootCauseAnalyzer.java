/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import com.example.smartassistant.common.eval.RootCauseAnalysis.Diagnosis;

/**
 * 根因分析器 — 实现文章《Agent 评测体系》§10 的根因 5 步：
 * <ol>
 *   <li><b>证据</b>：收集所有未通过结果 + 维度分；</li>
 *   <li><b>收敛</b>：按 {@link FailureSignature} 聚类；</li>
 *   <li><b>诊断</b>：映射失败类型 → 根因 + 责任角色（启发式表）；</li>
 *   <li><b>定责</b>：标注 {@link RootCauseTag}；</li>
 *   <li><b>落盘</b>：写入根因存储，生成修复工单建议（配置线/训练线/代码线）。</li>
 * </ol>
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public class RootCauseAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RootCauseAnalyzer.class);

    private final RootCauseStore store;

    public RootCauseAnalyzer() {
        this(new InMemoryRootCauseStore());
    }

    public RootCauseAnalyzer(RootCauseStore store) {
        this.store = store;
    }

    public RootCauseAnalysis analyze(List<AgentEvaluationResult> results) {
        // 1. 证据：仅取失败
        List<AgentEvaluationResult> failed = results.stream()
                .filter(r -> !r.passed())
                .toList();
        if (failed.isEmpty()) {
            return new RootCauseAnalysis(0, Map.of(), List.of(), false);
        }

        // 2. 收敛：按失败类型聚类
        Map<FailureSignature.FailureType, List<AgentEvaluationResult>> byType = failed.stream()
                .collect(Collectors.groupingBy(r -> FailureSignature.of(r).type()));

        Map<FailureSignature.FailureType, List<String>> clusters = new LinkedHashMap<>();
        List<RootCauseAnalysis.Diagnosis> diagnoses = new ArrayList<>();
        for (Map.Entry<FailureSignature.FailureType, List<AgentEvaluationResult>> e : byType.entrySet()) {
            FailureSignature.FailureType type = e.getKey();
            List<String> ids = e.getValue().stream().map(AgentEvaluationResult::getCaseId).toList();
            clusters.put(type, ids);

            // 3~4. 诊断 + 定责（启发式表）
            RootCauseAnalysis.Diagnosis base = DIAGNOSIS_TABLE.getOrDefault(type,
                    new RootCauseAnalysis.Diagnosis(type, "未知根因", RootCauseTag.UNKNOWN, "人工介入排查", ids));
            // 用真实样本替换表中的占位样本
            diagnoses.add(new Diagnosis(type, base.rootCause(), base.responsibleRole(),
                    base.recommendation(), ids));
        }

        // 5. 落盘
        RootCauseRecord rec = new RootCauseRecord(Instant.now().toString(), failed.size(), diagnoses);
        store.append(rec);

        log.info("[RootCause] 分析 {} 条失败 → {} 个簇", failed.size(), clusters.size());
        return new RootCauseAnalysis(failed.size(), clusters, diagnoses, true);
    }

    /** 生成修复工单建议（反馈闭环：配置线 / 训练线 / 代码线）。 */
    public List<String> generateFixTickets(RootCauseAnalysis analysis) {
        List<String> tickets = new ArrayList<>();
        for (RootCauseAnalysis.Diagnosis d : analysis.diagnoses()) {
            String line = switch (d.responsibleRole()) {
                case OPS -> "[配置线] 复核知识库/评测配置";
                case ALGORITHM -> "[训练线] 补充 SFT 正例 / RLHF 负例";
                case ENGINEERING -> "[代码线] 修复工具/Agent 逻辑";
                default -> "[人工] 线下排查";
            };
            tickets.add(String.format("%s | 根因=%s | 样本=%s | 建议=%s",
                    line, d.rootCause(), d.sampleCaseIds(), d.recommendation()));
        }
        return tickets;
    }

    // 失败类型 → 诊断（启发式知识表）
    private static final Map<FailureSignature.FailureType, RootCauseAnalysis.Diagnosis> DIAGNOSIS_TABLE =
            Map.of(
                    FailureSignature.FailureType.WRONG_INTENT, new Diagnosis(
                            FailureSignature.FailureType.WRONG_INTENT,
                            "意图分类器/路由对边界 query 区分度不足",
                            RootCauseTag.ALGORITHM,
                            "补充边界意图训练样本 + 收紧路由关键词", List.of()),
                    FailureSignature.FailureType.WRONG_TOOLS, new Diagnosis(
                            FailureSignature.FailureType.WRONG_TOOLS,
                            "工具 Schema 描述歧义或工具选择 Prompt 缺示例",
                            RootCauseTag.ENGINEERING,
                            "收窄工具参数 enum + 补充 few-shot 工具选择", List.of()),
                    FailureSignature.FailureType.MISSING_KEYWORDS, new Diagnosis(
                            FailureSignature.FailureType.MISSING_KEYWORDS,
                            "知识库召回缺失或回复模板漏字段",
                            RootCauseTag.OPS,
                            "扩充知识库覆盖 + 校验回复模板必填字段", List.of()),
                    FailureSignature.FailureType.ERROR, new Diagnosis(
                            FailureSignature.FailureType.ERROR,
                            "工具执行异常/超时/下游 500",
                            RootCauseTag.ENGINEERING,
                            "加熔断重试 + 检查工具幂等性(G1 同参数去重已防风暴)", List.of()),
                    FailureSignature.FailureType.LOW_OVERALL, new Diagnosis(
                            FailureSignature.FailureType.LOW_OVERALL,
                            "多项指标轻微不达标，无单点主因",
                            RootCauseTag.UNKNOWN,
                            "综合复盘 + 提升黄金集质量", List.of())
            );

    // ==================== 存储 ====================

    /** 根因记录存储接口。 */
    public interface RootCauseStore {
        void append(RootCauseRecord rec);

        List<RootCauseRecord> all();
    }

    /** 内存版根因存储。 */
    public static class InMemoryRootCauseStore implements RootCauseStore {
        private final List<RootCauseRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void append(RootCauseRecord rec) {
            records.add(rec);
        }

        @Override
        public List<RootCauseRecord> all() {
            return List.copyOf(records);
        }
    }

    /** 单次根因分析记录（可序列化落盘）。 */
    public record RootCauseRecord(String timestamp, int evidenceCount,
                                  List<RootCauseAnalysis.Diagnosis> diagnoses) {
    }
}
