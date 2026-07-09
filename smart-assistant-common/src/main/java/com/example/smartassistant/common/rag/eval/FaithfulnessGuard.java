/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.eval;

import java.util.List;

/**
 * 生产链路忠实度护栏（Faithfulness Guard）。
 * <p>
 * 对应面试题 Q⑩「减少幻觉（召回 / 生成 / 校验三层）」中的 <b>校验层</b>：
 * 复用离线评测已建成的 {@link HallucinationDetector}，在 Order / Product 的 RAG 回答
 * 最终产出后做后置校验——检测回答中的关键断言（数字 / 实体 / 否定 / 日期）是否被
 * 检索上下文支撑。命中高幻觉时 <b>非阻断</b>地追加免责声明并埋点，避免直接拒答损伤体验。
 * </p>
 * <p>
 * 设计要点（低阻断、可观测、可单测）：
 * <ul>
 *   <li>无上下文或回答为空 → 跳过（{@code checked=false}），不因「检不到上下文」误判为幻觉</li>
 *   <li>{@code hallucinationRate >= threshold} 才视为命中，阈值可在构造时调节（默认 0.6）</li>
 *   <li>{@link #guard(String, String)} 命中时返回「回答 + 免责声明」，未命中返回原回答</li>
 * </ul>
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-09
 */
public class FaithfulnessGuard {

    /** 默认幻觉率阈值：超过即视为命中 */
    private static final double DEFAULT_THRESHOLD = 0.6;

    /** 默认免责声明（命中高幻觉时追加到回答末尾） */
    private static final String DEFAULT_DISCLAIMER =
            "⚠️ 以上回答中有部分内容未能在检索到的资料中核实，仅供参考，请以官方信息为准。";

    private final double threshold;
    private final String disclaimer;
    private final HallucinationDetector detector;

    public FaithfulnessGuard() {
        this(DEFAULT_THRESHOLD, DEFAULT_DISCLAIMER);
    }

    public FaithfulnessGuard(double threshold, String disclaimer) {
        this.threshold = threshold;
        this.disclaimer = disclaimer;
        this.detector = new HallucinationDetector();
    }

    /**
     * 校验回答忠实度。
     *
     * @param answer   生成的回答（Agent 最终产出）
     * @param context  检索到的上下文（RAG 检索结果拼接）
     * @return 校验结论；无上下文 / 回答为空时 {@code checked=false}
     */
    public FaithfulnessVerdict check(String answer, String context) {
        // 无上下文或无回答 → 跳过（不因「检不到上下文」误判为幻觉）
        if (answer == null || answer.isBlank() || context == null || context.isBlank()) {
            return new FaithfulnessVerdict(false, false, 0.0, List.of(), null);
        }
        HallucinationDetector.HallucinationResult result = detector.detect(answer, context);
        boolean hit = result.hasHallucination() && result.hallucinationRate() >= threshold;
        String message = hit ? disclaimer : null;
        return new FaithfulnessVerdict(true, hit, result.hallucinationRate(), result.claims(), message);
    }

    /**
     * 校验并兜底：命中高幻觉时返回「回答 + 免责声明」，否则返回原回答。
     *
     * @param answer   生成的回答
     * @param context  检索到的上下文
     * @return 处理后（可能追加声明）的回答
     */
    public String guard(String answer, String context) {
        FaithfulnessVerdict verdict = check(answer, context);
        if (verdict.hallucination() && verdict.message() != null) {
            return answer + "\n\n" + verdict.message();
        }
        return answer;
    }

    /** 校验结论 */
    public record FaithfulnessVerdict(
            /** 是否实际执行了校验（无上下文 / 无回答时为 false） */
            boolean checked,
            /** 是否命中高幻觉 */
            boolean hallucination,
            /** 幻觉率（0.0~1.0） */
            double score,
            /** 命中的幻觉断言列表 */
            List<HallucinationDetector.HallucinationClaim> claims,
            /** 命中时返回的免责声明；未命中为 null */
            String message
    ) {}
}
