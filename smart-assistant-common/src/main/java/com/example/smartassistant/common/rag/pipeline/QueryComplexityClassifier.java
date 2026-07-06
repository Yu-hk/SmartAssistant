/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 轻量查询复杂度分类器——无需 LLM 调用，基于规则在亚毫秒内判断。
 *
 * <p>参考文章三段式路由中的「复杂度评估」：
 * <ul>
 *   <li>简单查询（SIMPLE）：常规问答、查订单状态、天气查询等单步任务</li>
 *   <li>中等查询（MEDIUM）：需要多步推理、参数提取、纠错等</li>
 *   <li>复杂查询（COMPLEX）：退款投诉、跨模块协作、多意图、情感分析</li>
 * </ul>
 *
 * <p>分类结果可供 Router 选择模型：简单→7B本地，复杂→DeepSeek/gpt-4o。
 * 纯规则实现，无 LLM 调用延迟（< 1ms）。
 */
public class QueryComplexityClassifier {

    private static final Logger log = LoggerFactory.getLogger(QueryComplexityClassifier.class);

    public enum Complexity { SIMPLE, MEDIUM, COMPLEX }

    /** 复杂查询关键词（匹配到任意一个 → COMPLEX） */
    private static final List<Pattern> COMPLEX_PATTERNS = List.of(
            Pattern.compile("(?i)(投诉|举报|起诉|律师|法律|维权|赔偿|退款)"),
            Pattern.compile("(?i)(比较|区别|差异|对比|哪个好|推荐|选择)"),
            Pattern.compile("(?i)(为什么|原因|原理|如何实现|工作原理|机制)"),
            Pattern.compile("(?i)(帮我分析|评估|建议|规划|方案|计划)"),
            Pattern.compile("(?i)(多步|同时|然后|接着|再|一步步)"),
            Pattern.compile("(?i)(以前|之前|刚才|上次|之前说过)"),
            Pattern.compile("(?i)(如果|假如|假设|要是|万一|当.*时)")
    );

    /** 简单查询关键词（匹配到多个 → SIMPLE） */
    private static final List<Pattern> SIMPLE_PATTERNS = List.of(
            Pattern.compile("(?i)(查|查询|看|找|搜|搜一下|看看)"),
            Pattern.compile("(?i)(订单|物流|快递|商品|价格|库存)"),
            Pattern.compile("(?i)(多少|哪里|什么|谁|什么时候|几点)"),
            Pattern.compile("(?i)(天气|时间|日期|今天|明天|昨天)"),
            Pattern.compile("(?i)(计算|转换|换算|多少.*钱)")
    );

    /**
     * 分类查询复杂度。
     *
     * @param query 用户原始查询
     * @return Complexity 等级
     */
    public Complexity classify(String query) {
        if (query == null || query.isBlank()) return Complexity.SIMPLE;

        int complexMatches = countMatches(query, COMPLEX_PATTERNS);
        int simpleMatches = countMatches(query, SIMPLE_PATTERNS);

        // 短查询（≤5字）且无复杂词 → 简单
        if (query.length() <= 5 && complexMatches == 0) {
            return Complexity.SIMPLE;
        }

        // 复杂词多 → COMPLEX
        if (complexMatches >= 2) return Complexity.COMPLEX;

        // 有复杂词 + 长文本 → COMPLEX
        if (complexMatches >= 1 && query.length() > 20) return Complexity.COMPLEX;

        // 简单词多且无复杂词 → SIMPLE
        if (simpleMatches >= 2 && complexMatches == 0) return Complexity.SIMPLE;

        // 包含明确疑问词 → SIMPLE
        if (simpleMatches >= 1 && complexMatches == 0) return Complexity.SIMPLE;

        // 默认中等
        return Complexity.MEDIUM;
    }

    /**
     * 获取推荐的模型层级。
     *
     * @return "light"（本地 7B）/ "standard"（本地 72B）/ "heavy"（DeepSeek）
     */
    public String suggestModel(String query) {
        return switch (classify(query)) {
            case SIMPLE -> "light";
            case MEDIUM -> "standard";
            case COMPLEX -> "heavy";
        };
    }

    private int countMatches(String text, List<Pattern> patterns) {
        int count = 0;
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) count++;
        }
        return count;
    }
}
