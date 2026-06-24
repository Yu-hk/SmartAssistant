/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.evaluation;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图引导的查询改写服务——根据意图类型选择不同改写策略。
 * <p>
 * 对应文章「意图识别」中"搭配 Query 改写"一节：
 * <ul>
 *   <li><b>多跳推理意图</b> → 查询分解（Sub-query Decomposition）</li>
 *   <li><b>模糊扩展意图</b> → 查询扩展（HyDE / 同义扩展）</li>
 *   <li><b>对话指代意图</b> → 指代消解改写</li>
 *   <li><b>精确查找意图</b> → 保留原始查询或轻度规范化</li>
 * </ul>
 * </p>
 */
@Service
public class IntentGuidedQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(IntentGuidedQueryRewriter.class);

    /** 重写结果 */
    public record RewriteResult(
            String rewrittenQuery,
            String rewriteStrategy,
            List<String> subQueries
    ) {}

    /**
     * 根据任务分析结果改写用户查询。
     *
     * @param question  用户原始查询
     * @param analysis  任务分析结果
     * @return 改写后的查询结果
     */
    public RewriteResult rewrite(String question, TaskAnalysisResult analysis) {
        if (question == null || question.isBlank()) {
            return new RewriteResult(question, "none", Collections.emptyList());
        }

        String intentCategory = analysis != null ? analysis.getIntentCategory() : null;
        Map<String, Object> entities = analysis != null ? analysis.getEntities() : new HashMap<>();

        // 1. 已标准化的输入优先使用
        String baseQuery = question;
        if (analysis != null && analysis.getStandardizedInput() != null) {
            baseQuery = analysis.getStandardizedInput();
        }

        // 2. 根据意图选择改写策略
        if (intentCategory == null || "UNKNOWN".equals(intentCategory)) {
            return new RewriteResult(baseQuery, "none", Collections.emptyList());
        }

        // 多意图 → 查询分解
        if (analysis != null && analysis.hasSubIntents()) {
            return decomposeMultiIntent(baseQuery, analysis);
        }

        // 模糊意图 → 查询扩展
        if (isFuzzyIntent(intentCategory)) {
            return expandQuery(baseQuery, analysis);
        }

        // ORDER/PRODUCT 精确查询 → 保留或轻度规范化
        if ("ORDER".equals(intentCategory) || "PRODUCT".equals(intentCategory)) {
            return new RewriteResult(baseQuery, "precision", Collections.emptyList());
        }

        // GENERAL → 标准化但不改写
        if ("GENERAL".equals(intentCategory)) {
            return new RewriteResult(baseQuery, "normalized", Collections.emptyList());
        }

        // COMPLEX → 尝试分解
        if ("COMPLEX".equals(intentCategory)) {
            return new RewriteResult(baseQuery, "complex_keep", Collections.emptyList());
        }

        return new RewriteResult(baseQuery, "none", Collections.emptyList());
    }

    // ==================== 策略实现 ====================

    /**
     * 多意图分解：将一句话中的多个子任务拆为独立查询。
     */
    private RewriteResult decomposeMultiIntent(String question, TaskAnalysisResult analysis) {
        List<String> subQueries = new ArrayList<>();

        for (Map<String, Object> subIntent : analysis.getSubIntents()) {
            String description = (String) subIntent.get("description");
            if (description != null && !description.isBlank()) {
                subQueries.add(description);
            }
        }

        // 如果没有从 sub_intents 拿到描述，尝试从文本拆分
        if (subQueries.isEmpty()) {
            subQueries.add(question);
        }

        String rewritten = subQueries.size() > 1
                ? String.join(" | ", subQueries)
                : question;

        return new RewriteResult(rewritten, "decomposition", subQueries);
    }

    /**
     * 模糊查询扩展：补充同义词和上下文。
     */
    private RewriteResult expandQuery(String question, TaskAnalysisResult analysis) {
        StringBuilder expanded = new StringBuilder(question);
        Map<String, Object> entities = analysis != null ? analysis.getEntities() : new HashMap<>();

        // 补充地点信息
        if (entities.containsKey("location")) {
            expanded.append(" ").append(entities.get("location"));
        }

        // 补充日期信息
        if (entities.containsKey("date")) {
            expanded.append(" ").append(entities.get("date"));
        }

        // 补充金额/价格约束
        if (entities.containsKey("amount")) {
            expanded.append(" 价格约").append(entities.get("amount"));
        }

        String result = expanded.toString();
        if (!result.equals(question)) {
            log.debug("[QueryRewriter] 模糊扩展: '{}' → '{}'", question, result);
        }

        return new RewriteResult(result, "expansion", Collections.singletonList(result));
    }

    /** 判断是否为模糊意图（需要扩展） */
    private boolean isFuzzyIntent(String intentCategory) {
        if (intentCategory == null) return false;
        String lower = intentCategory.toLowerCase();
        return lower.contains("complex") || lower.contains("general")
                || lower.contains("模糊") || lower.contains("搜索")
                || lower.contains("search") || lower.contains("query");
    }

    // ==================== 工具方法 ====================

    /** 简单指代消解：查找并替换对话中的代词 */
    public static String resolveAnaphora(String currentQuery, String historyContext) {
        if (historyContext == null || historyContext.isBlank()) return currentQuery;

        String resolved = currentQuery;

        // 替换"它/他们/这/那"为历史中的关键实体
        List<String> pronouns = List.of("它", "它们", "这个", "那个", "这些", "那些");
        for (String pronoun : pronouns) {
            if (resolved.contains(pronoun)) {
                // 从历史中提取最后一个名词短语作为候选
                String candidate = extractLastEntity(historyContext);
                if (candidate != null) {
                    resolved = resolved.replace(pronoun, candidate);
                }
            }
        }

        return resolved;
    }

    /** 从历史文本中提取最后一个实体名词 */
    private static String extractLastEntity(String text) {
        // 简单策略：查找订单号、商品名、站名等
        Pattern entityPattern = Pattern.compile(
                "(?:订单|商品|车次|票)([A-Z0-9a-z\\u4e00-\\u9fff]+)");
        Matcher matcher = entityPattern.matcher(text);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }
}
