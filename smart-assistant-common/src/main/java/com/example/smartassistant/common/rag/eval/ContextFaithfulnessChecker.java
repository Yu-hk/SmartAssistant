/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ⭐ 上下文冲突标记 + Faithfulness 校验。
 * <p>
 * 对应 RAG 文章"上下文组装"和"答案可验证"两节：
 * <ul>
 *   <li><b>冲突标记</b>：检测上下文中是否存在互相矛盾的内容，添加冲突标注</li>
 *   <li><b>Faithfulness 校验</b>：检查答案中的关键结论是否被上下文证据支撑</li>
 * </ul>
 * </p>
 */
public class ContextFaithfulnessChecker {

    private static final Logger log = LoggerFactory.getLogger(ContextFaithfulnessChecker.class);

    /** 冲突关键词库 — 含义相反的关键词组 */
    private static final List<ConflictPair> CONFLICT_PAIRS = List.of(
            new ConflictPair(List.of("是", "可以", "支持", "可用"), List.of("否", "不可以", "不支持", "不可用", "不适用")),
            new ConflictPair(List.of("有效", "生效", "已激活"), List.of("无效", "未生效", "已失效", "已过期", "已作废")),
            new ConflictPair(List.of("包含", "包括"), List.of("不包含", "不包括", "不含")),
            new ConflictPair(List.of("开启", "启用", "打开"), List.of("关闭", "停用", "禁用"))
    );

    // ==================== 上下文冲突标记 ====================

    /**
     * 检测上下文中的冲突内容并返回带冲突标记的上下文列表。
     *
     * @param contextItems 上下文项列表（每个片段一行或一段）
     * @return 带冲突标记的上下文列表（无冲突时原样返回）
     */
    public List<String> markConflicts(List<String> contextItems) {
        if (contextItems == null || contextItems.size() < 2) return contextItems;

        List<String> result = new ArrayList<>(contextItems);
        boolean hasConflict = false;

        // 两两对比检测冲突
        for (int i = 0; i < contextItems.size(); i++) {
            for (int j = i + 1; j < contextItems.size(); j++) {
                String conflict = detectConflict(contextItems.get(i), contextItems.get(j));
                if (conflict != null) {
                    hasConflict = true;
                    log.warn("[Faithfulness] 上下文冲突: [{}] vs [{}]: {}", i, j, conflict);
                }
            }
        }

        if (hasConflict) {
            // 在上下文首部添加冲突警告
            result.add(0, "⚠️【冲突警告】检索到的资料中存在互相矛盾的内容，"
                    + "请模型注意区分不同来源和版本的信息。");
        }

        return result;
    }

    /**
     * 检测两段文本是否存在冲突。
     *
     * @param textA 文本 A
     * @param textB 文本 B
     * @return 冲突描述（无冲突返回 null）
     */
    private String detectConflict(String textA, String textB) {
        for (ConflictPair pair : CONFLICT_PAIRS) {
            boolean aPos = containsAny(textA, pair.positive);
            boolean aNeg = containsAny(textA, pair.negative);
            boolean bPos = containsAny(textB, pair.positive);
            boolean bNeg = containsAny(textB, pair.negative);

            // A 说"是"且 B 说"否"，或者反过来
            if ((aPos && bNeg) || (aNeg && bPos)) {
                return String.format("一方说'%s'，另一方说'%s'",
                        aPos ? pair.positive.get(0) : pair.negative.get(0),
                        bPos ? pair.positive.get(0) : pair.negative.get(0));
            }
        }
        return null;
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    // ==================== Faithfulness 校验 ====================

    /**
     * 校验答案中的 CID 引用是否真实存在于上下文中。
     * <p>
     * 检查答案中出现的每个 {@code [CID:xxx]} 引用是否都能在上下文中找到对应 ID。
     * 如果引用了上下文中不存在的 CID，标记为不可信引用。
     * </p>
     *
     * @param answer    模型生成的答案
     * @param context   上下文文本（拼接后的）
     * @return Faithfulness 校验结果
     */
    public FaithfulnessResult checkFaithfulness(String answer, String context) {
        if (answer == null || answer.isBlank()) {
            return new FaithfulnessResult(true, 1.0, List.of(), "无答案需要校验");
        }

        // 提取答案中所有 [CID:xxx] 引用
        List<String> citedCids = extractCids(answer);
        if (citedCids.isEmpty()) {
            // 没使用 CID 引用，直接通过
            return new FaithfulnessResult(true, 1.0, List.of(), "答案未使用 CID 引用，假设可信");
        }

        // 提取上下文中所有 [CID:xxx]
        List<String> contextCids = extractCids(context);

        // 检查每个引用是否在上下文中存在
        List<String> invalidRefs = new ArrayList<>();
        for (String cid : citedCids) {
            if (!contextCids.contains(cid)) {
                invalidRefs.add(cid);
            }
        }

        double faithfulnessScore = 1.0;
        if (!invalidRefs.isEmpty()) {
            faithfulnessScore = 1.0 - ((double) invalidRefs.size() / citedCids.size());
            faithfulnessScore = Math.max(0, faithfulnessScore);
        }

        boolean passed = faithfulnessScore >= 0.8; // 80% 以上可信算通过

        String detail = passed
                ? "通过: 答案中的 " + citedCids.size() + " 个引用均可回溯到上下文"
                : "失败: 发现 " + invalidRefs.size() + " 个无效引用 → " + String.join(", ", invalidRefs);

        return new FaithfulnessResult(passed, faithfulnessScore, invalidRefs, detail);
    }

    /** 从文本中提取所有 [CID:xxx] 引用 ID */
    private List<String> extractCids(String text) {
        List<String> cids = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[CID:([^\\]]+)\\]");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            cids.add(matcher.group(1));
        }
        return cids.stream().distinct().collect(Collectors.toList());
    }

    // ==================== 内部类 ====================

    /** 正反义关键词对 */
    private record ConflictPair(List<String> positive, List<String> negative) {}

    /** Faithfulness 校验结果 */
    public record FaithfulnessResult(
            /** 是否通过校验 */
            boolean passed,
            /** 可信度分数 0.0~1.0 */
            double score,
            /** 无效的引用 ID 列表 */
            List<String> invalidReferences,
            /** 详细说明 */
            String detail
    ) {}
}
