/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.retrieval;

import com.example.smartassistant.common.rag.AuthorityLevel;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeHit;
import com.example.smartassistant.common.rag.eval.ContextFaithfulnessChecker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ⭐ 检索侧跨文档冲突消解（RAG 七连问·第六问「冲突处理」第二层）。
 * <p>
 * 与 {@link ContextFaithfulnessChecker} 在「答案组装阶段」做上下文内冲突标记不同，
 * 本组件在<b>检索重排之后</b>对候选 chunk 做<b>跨文档权威性冲突消解</b>：
 * <ol>
 *   <li>复用 {@link ContextFaithfulnessChecker#detectConflict} 的正反义词表检测语义冲突；</li>
 *   <li>冲突双方按 <b>AuthorityLevel 权威性 → 版本优先级</b> 判定胜负；</li>
 *   <li>败方按相对扣分率 {@code conflictPenaltyRate} 降权，胜方保持原分；平局双方减半扣分；</li>
 *   <li>输出带 {@link ScoreBreakdown} 可观测明细的 {@link ResolvedHit} 与审计用 {@link ConflictDecision}。</li>
 * </ol>
 * </p>
 *
 * <p>设计原则：纯函数、无基础设施依赖、可单元测试；不修改原始文档，仅在分数层面消解。</p>
 */
public class CrossDocumentConflictResolver {

    /** 冲突败方相对扣分率（作用于 baseScore，默认 0.30 → 败方保留 70% 分） */
    private final double conflictPenaltyRate;

    public CrossDocumentConflictResolver() {
        this(0.30);
    }

    /**
     * @param conflictPenaltyRate 冲突败方相对扣分率，需在 (0,1) 区间，越界回落默认 0.30
     */
    public CrossDocumentConflictResolver(double conflictPenaltyRate) {
        this.conflictPenaltyRate = (conflictPenaltyRate > 0 && conflictPenaltyRate < 1)
                ? conflictPenaltyRate : 0.30;
    }

    // ==================== 对外 API ====================

    /**
     * 完整消解：返回每个候选的调整后分数（含可观测明细）与逐对冲突决策。
     *
     * @param candidates 重排后的候选列表（按分数降序，允许任意顺序）
     * @return 消解结果（candidates 为空/单元素时原样透传）
     */
    public ConflictResolutionResult resolve(List<KnowledgeHit> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ConflictResolutionResult(List.of(), List.of());
        }
        if (candidates.size() == 1) {
            KnowledgeHit h = candidates.get(0);
            return new ConflictResolutionResult(
                    List.of(passthrough(h)), List.of());
        }

        List<ConflictDecision> decisions = new ArrayList<>();
        Map<String, Double> penaltyByDocId = new HashMap<>();
        Map<String, List<ConflictTag>> tagsByDocId = new HashMap<>();

        // 两两检测冲突并消解
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                KnowledgeHit a = candidates.get(i);
                KnowledgeHit b = candidates.get(j);
                String detail = ContextFaithfulnessChecker.detectConflict(
                        a.getDocument().getContent(), b.getDocument().getContent());
                if (detail == null) continue;

                ResolutionOutcome outcome = judge(a.getDocument(), b.getDocument());
                decisions.add(new ConflictDecision(
                        a.getDocument().getId(), b.getDocument().getId(),
                        outcome.winnerId(), outcome.reason(), detail));

                applyPenalty(penaltyByDocId, tagsByDocId, a, b, outcome);
            }
        }

        // 计算调整后分数并重新排序
        List<ResolvedHit> resolved = new ArrayList<>();
        for (KnowledgeHit h : candidates) {
            String id = h.getDocument().getId();
            double penalty = penaltyByDocId.getOrDefault(id, 0.0);
            double adjusted = h.getScore() * (1.0 - penalty);
            AuthorityLevel auth = h.getDocument().getAuthorityLevel();
            ScoreBreakdown bd = new ScoreBreakdown(
                    h.getScore(), auth.getRank() / 4.0, penalty, adjusted);
            resolved.add(new ResolvedHit(h, adjusted, bd,
                    tagsByDocId.getOrDefault(id, List.of())));
        }
        resolved.sort(Comparator.comparingDouble(ResolvedHit::adjustedScore).reversed());
        return new ConflictResolutionResult(resolved, decisions);
    }

    /**
     * 便捷方法：仅返回调整后的 {@link KnowledgeHit} 列表（供 {@code KnowledgeBase.search} 直接替换返回）。
     */
    public List<KnowledgeHit> resolveHits(List<KnowledgeHit> candidates) {
        return resolve(candidates).resolved().stream()
                .map(r -> new KnowledgeHit(r.original().getDocument(), r.adjustedScore()))
                .collect(Collectors.toList());
    }

    // ==================== 内部逻辑 ====================

    /**
     * 判定冲突双方胜负：先比权威性 rank（大者胜），再比版本优先级（大者胜），均相同则平局。
     */
    private ResolutionOutcome judge(KnowledgeDocument a, KnowledgeDocument b) {
        int ra = a.getAuthorityLevel().getRank();
        int rb = b.getAuthorityLevel().getRank();
        if (ra != rb) {
            return ra > rb
                    ? new ResolutionOutcome(a.getId(), "AUTHORITY")
                    : new ResolutionOutcome(b.getId(), "AUTHORITY");
        }
        double va = a.getVersionPriority();
        double vb = b.getVersionPriority();
        if (Math.abs(va - vb) > 1e-9) {
            return va > vb
                    ? new ResolutionOutcome(a.getId(), "VERSION")
                    : new ResolutionOutcome(b.getId(), "VERSION");
        }
        return new ResolutionOutcome(null, "TIE");
    }

    /** 依判定结果对败方（或平局双方）应用扣分与冲突标记 */
    private void applyPenalty(Map<String, Double> penaltyByDocId,
                              Map<String, List<ConflictTag>> tagsByDocId,
                              KnowledgeHit a, KnowledgeHit b,
                              ResolutionOutcome outcome) {
        double full = conflictPenaltyRate;
        double tieRate = conflictPenaltyRate / 2.0;

        if (outcome.winnerId() == null) {
            // 平局：双方减半扣分并互相标记
            addPenalty(penaltyByDocId, a.getDocument().getId(), tieRate);
            addPenalty(penaltyByDocId, b.getDocument().getId(), tieRate);
            addTag(tagsByDocId, a.getDocument().getId(),
                    new ConflictTag(b.getDocument().getId(), "TIE", false));
            addTag(tagsByDocId, b.getDocument().getId(),
                    new ConflictTag(a.getDocument().getId(), "TIE", false));
        } else if (outcome.winnerId().equals(a.getDocument().getId())) {
            addPenalty(penaltyByDocId, b.getDocument().getId(), full);
            addTag(tagsByDocId, b.getDocument().getId(),
                    new ConflictTag(a.getDocument().getId(), outcome.reason(), true));
        } else {
            addPenalty(penaltyByDocId, a.getDocument().getId(), full);
            addTag(tagsByDocId, a.getDocument().getId(),
                    new ConflictTag(b.getDocument().getId(), outcome.reason(), true));
        }
    }

    private static void addPenalty(Map<String, Double> m, String id, double p) {
        m.merge(id, p, Double::sum);
    }

    private static void addTag(Map<String, List<ConflictTag>> m, String id, ConflictTag t) {
        m.computeIfAbsent(id, k -> new ArrayList<>()).add(t);
    }

    private ResolvedHit passthrough(KnowledgeHit h) {
        AuthorityLevel auth = h.getDocument().getAuthorityLevel();
        ScoreBreakdown bd = new ScoreBreakdown(
                h.getScore(), auth.getRank() / 4.0, 0.0, h.getScore());
        return new ResolvedHit(h, h.getScore(), bd, List.of());
    }

    // ==================== 结果类型 ====================

    /** 消解总结果 */
    public record ConflictResolutionResult(
            /** 按调整后分数降序的候选 */
            List<ResolvedHit> resolved,
            /** 逐对冲突的消解决策（审计/可观测） */
            List<ConflictDecision> decisions
    ) {}

    /** 单候选消解结果 */
    public record ResolvedHit(
            /** 原始命中（文档与原始分数可追溯） */
            KnowledgeHit original,
            /** 冲突消解后的分数 */
            double adjustedScore,
            /** 分数构成明细（可观测性） */
            ScoreBreakdown breakdown,
            /** 该候选参与的冲突标记 */
            List<ConflictTag> conflictTags
    ) {}

    /** 分数构成明细 */
    public record ScoreBreakdown(
            /** rerank 后的原始分数 */
            double baseScore,
            /** 权威性因子 = rank/4（L1=1.0 … L4=0.25） */
            double authorityFactor,
            /** 因冲突被扣减的相对比率（0~1，0 表示无冲突） */
            double conflictPenalty,
            /** 最终分数 = baseScore × (1 - conflictPenalty) */
            double finalScore
    ) {}

    /** 冲突标记（标注与谁冲突、因何、是否败方） */
    public record ConflictTag(
            /** 冲突对方文档 ID */
            String conflictingWithDocId,
            /** 消解依据：AUTHORITY / VERSION / TIE */
            String reason,
            /** true=败方被扣分；false=平局双方均扣分 */
            boolean lost
    ) {}

    /** 一对冲突的消解决策（审计用） */
    public record ConflictDecision(
            /** 文档 A ID */
            String docIdA,
            /** 文档 B ID */
            String docIdB,
            /** 胜方文档 ID（平局为 null） */
            String winnerDocId,
            /** 消解依据：AUTHORITY / VERSION / TIE */
            String reason,
            /** 来自词表的冲突描述 */
            String conflictDetail
    ) {}

    /** 内部：一对文档的胜负判定 */
    private record ResolutionOutcome(String winnerId, String reason) {}
}
