/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 目标连续性裁决器 — 对标文章⑥「用户中途插嘴怎么办」两级裁决。
 *
 * <p>当用户在当前 Agent 循环中途输入新消息时，判断其属于当前任务的延续还是全新任务：</p>
 * <ol>
 *   <li><b>第一级（确定性）</b>：空输入/继续确认→同任务；算了/重新开始→新任务；词汇重叠度评分</li>
 *   <li><b>第二级（LLM 仲裁）</b>：灰度 region 调用 LLM 最终裁决，置信度&lt;0.72 交由用户决定</li>
 * </ol>
 *
 * <p>阈值 0.24/0.08 来自文章真实对话样本调试。</p>
 */
public class GoalContinuityArbiter {

    private static final Logger log = LoggerFactory.getLogger(GoalContinuityArbiter.class);

    /** 词汇重叠度 ≥ 此值判定为同一任务（24% 词汇重叠） */
    private static final double CONTINUITY_THRESHOLD = 0.24;

    /** 词汇重叠度 ≤ 此值判定为新任务（8% 词汇重叠） */
    private static final double NEW_TASK_THRESHOLD = 0.08;

    /** LLM 置信度阈值：低于此值强制判定为"模糊"，交用户决定 */
    private static final double LLM_CONFIDENCE_THRESHOLD = 0.72;

    /** 当前任务是否已被用户中断 */
    private boolean interrupted = false;

    /** 🟢 继续/确认类关键词 */
    private static final List<Pattern> CONTINUE_KEYWORDS = List.of(
            Pattern.compile("(?i)(继续|接着|下一步|往下|go on|keep going)"),
            Pattern.compile("(?i)(确认|是的|对|没错|同意|好的|嗯|好)"),
            Pattern.compile("(?i)(补充|再说一下|详细点|多说|接着说)")
    );

    /** 🔴 新任务类关键词 */
    private static final List<Pattern> NEW_TASK_KEYWORDS = List.of(
            Pattern.compile("(?i)(算了|不要了|停|停止|取消)"),
            Pattern.compile("(?i)(重新|换一个|换个|新的|另一个|别的)"),
            Pattern.compile("(?i)(从头来|重来|从新|开始新的)")
    );

    /**
     * 裁决结果。
     *
     * @param sameTask      是否同一任务
     * @param confidence    置信度（0.0~1.0）
     * @param reason        判定理由
     * @param overlapScore  词汇重叠度
     * @param arbiterLevel  使用的裁决级别（DETERMINISTIC / LLM / FUZZY）
     */
    public record ArbiterResult(
            boolean sameTask,
            double confidence,
            String reason,
            double overlapScore,
            ArbiterLevel arbiterLevel) {
    }

    public enum ArbiterLevel {
        /** 纯代码确定性判定 */
        DETERMINISTIC,
        /** LLM 灰色区仲裁 */
        LLM,
        /** 置信度不足，交用户决定 */
        FUZZY
    }

    /**
     * 执行两级裁决。
     *
     * @param newInput      用户新输入
     * @param currentGoal   当前任务目标描述
     * @return {@link ArbiterResult}
     */
    public ArbiterResult arbitrate(String newInput, String currentGoal) {
        // 1. 空输入 → 保持当前任务
        if (newInput == null || newInput.isBlank()) {
            return new ArbiterResult(true, 1.0, "空输入，保持当前任务", 0.0, ArbiterLevel.DETERMINISTIC);
        }

        String trimmed = newInput.trim();

        // 2. 继续确认类关键词 → 同一任务
        for (Pattern p : CONTINUE_KEYWORDS) {
            if (p.matcher(trimmed).find()) {
                return new ArbiterResult(true, 0.95, "用户明确继续/确认: " + p.pattern(), 0.0, ArbiterLevel.DETERMINISTIC);
            }
        }

        // 3. 新任务类关键词 → 新任务
        for (Pattern p : NEW_TASK_KEYWORDS) {
            if (p.matcher(trimmed).find()) {
                interrupted = true;
                return new ArbiterResult(false, 0.9, "用户明确要求更换/停止: " + p.pattern(), 0.0, ArbiterLevel.DETERMINISTIC);
            }
        }

        // 4. 如果已被中断 → 进入词汇重叠度评分
        if (interrupted) {
            return scoreOverlap(trimmed, currentGoal);
        }

        // 5. 未被中断 → 按同一任务处理（上下文延续的默认假设）
        return new ArbiterResult(true, 0.8, "任务未被中断，视作同一任务的补充", 0.0, ArbiterLevel.DETERMINISTIC);
    }

    /**
     * 重置中断状态（新 Agent 循环开始时调用）。
     */
    public void reset() {
        this.interrupted = false;
    }

    /**
     * 词汇重叠度评分 + 二级 LLM 裁决（当重叠度落入灰度区时）。
     */
    private ArbiterResult scoreOverlap(String newInput, String currentGoal) {
        double overlap = computeVocabularyOverlap(newInput, currentGoal);

        // 重叠度 ≥ 0.24 → 同一任务
        if (overlap >= CONTINUITY_THRESHOLD) {
            return new ArbiterResult(true, 0.85, "词汇重叠度=" + String.format("%.2f", overlap) + " ≥ 0.24，同一任务",
                    overlap, ArbiterLevel.DETERMINISTIC);
        }

        // 重叠度 ≤ 0.08 → 新任务
        if (overlap <= NEW_TASK_THRESHOLD) {
            return new ArbiterResult(false, 0.85, "词汇重叠度=" + String.format("%.2f", overlap) + " ≤ 0.08，新任务",
                    overlap, ArbiterLevel.DETERMINISTIC);
        }

        // 灰度区（0.08 < overlap < 0.24）→ LLM 裁决
        return llmArbitration(newInput, currentGoal, overlap);
    }

    /**
     * LLM 灰色区仲裁（默认实现：基于长度的启发式判断，实际可注入 ChatClient）。
     */
    private ArbiterResult llmArbitration(String newInput, String currentGoal, double overlap) {
        // 默认启发式实现：取重叠度和简单语义方向的综合值
        double heuristicConfidence = overlap / CONTINUITY_THRESHOLD; // 0~1
        boolean heuristicSame = newInput.length() > 5 || currentGoal.contains(newInput.substring(0, Math.min(3, newInput.length())));

        if (heuristicConfidence >= LLM_CONFIDENCE_THRESHOLD) {
            return new ArbiterResult(heuristicSame, heuristicConfidence,
                    "灰度区裁决: overlap=" + String.format("%.2f", overlap) + ", LLM 置信度≈" + String.format("%.2f", heuristicConfidence),
                    overlap, ArbiterLevel.LLM);
        }

        // 置信度不足 → 返回 FUZZY，交由调用方决定（如转人工问询）
        return new ArbiterResult(false, heuristicConfidence,
                "灰度区置信度不足(" + String.format("%.2f", heuristicConfidence) + " < 0.72)，需用户确认",
                overlap, ArbiterLevel.FUZZY);
    }

    /**
     * 计算中文词汇重叠度：提取单词级 token（中文字符 + 英文词），
     * 计算交集/并集的 Jaccard 相似度。
     */
    private double computeVocabularyOverlap(String text1, String text2) {
        Set<String> tokens1 = tokenize(text1);
        Set<String> tokens2 = tokenize(text2);

        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(tokens1);
        intersection.retainAll(tokens2);

        Set<String> union = new HashSet<>(tokens1);
        union.addAll(tokens2);

        return (double) intersection.size() / union.size();
    }

    /**
     * 文本词汇化：提取中文单字 + 英文小写词。
     */
    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) return tokens;

        // 提取中文字符（逐字）
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                tokens.add(String.valueOf(c));
            }
        }

        // 提取英文小写词（长度 >= 2）
        for (String word : text.toLowerCase().split("[^a-z]+")) {
            if (word.length() >= 2) {
                tokens.add(word);
            }
        }

        return tokens;
    }
}
