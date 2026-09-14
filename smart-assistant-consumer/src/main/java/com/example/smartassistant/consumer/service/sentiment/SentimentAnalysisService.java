/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.sentiment;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * ⭐ 情感分析服务 — 识别用户情绪并建议处理策略。
 * <p>
 * 对应客服Agent实战系列 06 文章：5级情感(正面→愤怒)、关键词+LLM混合、
 * 情绪等级只提供处理建议，不代表已发起人工转接；跨轮状态由 SentimentSnapshotStore 管理。
 * </p>
 *
 * <h3>情感等级</h3>
 * <table>
 *   <tr><th>等级</th><th>名称</th><th>处理策略</th></tr>
 *   <tr><td>1</td><td>正面</td><td>礼貌感谢</td></tr>
 *   <tr><td>2</td><td>中性</td><td>正常回复</td></tr>
 *   <tr><td>3</td><td>轻微负面</td><td>道歉+改进建议</td></tr>
 *   <tr><td>4</td><td>负面</td><td>道歉+加快处理</td></tr>
 *   <tr><td>5</td><td>愤怒</td><td>共情并建议人工协助</td></tr>
 * </table>
 */
@Service
public class SentimentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SentimentAnalysisService.class);

    /** 5级情感定义（大幅扩展关键词库） */
    private static final List<SentimentLevel> SENTIMENT_LEVELS = List.of(
            new SentimentLevel(1, "正面", "礼貌感谢", List.of(
                    "谢谢", "感谢", "好的", "满意", "很棒", "不错", "好评",
                    "太棒了", "非常好", "很满意", "赞", "给力", "优秀",
                    "效率高", "服务好", "靠谱", "专业", "贴心", "周到",
                    "速度快", "体验好", "超出预期", "值得推荐", "辛苦了")),
            new SentimentLevel(2, "中性", "正常回复", List.of(
                    "你好", "在吗", "请问", "咨询", "查询", "了解一下",
                    "帮我", "看看", "我想", "有没有", "怎么", "为什么",
                    "什么", "多少", "哪里", "哪个", "能否", "可以吗")),
            new SentimentLevel(3, "轻微负面", "道歉+改进建议", List.of(
                    "有点慢", "不太方便", "还行吧", "不怎么好",
                    "有点麻烦", "不太满意", "能不能快点", "等很久了",
                    "一般般", "凑合", "马马虎虎", "不怎么样", "不太好",
                    "有点失望", "不够好", "不算好", "不太行")),
            new SentimentLevel(4, "负面", "道歉+加快处理", List.of(
                    "太慢了", "等了很久", "不满意", "差劲", "太差",
                    "非常失望", "太糟糕", "服务差", "效率低", "太离谱",
                    "受不了", "忍不了", "搞什么", "怎么回事", "太差劲",
                    "太让人失望", "很不满意", "体验很差", "浪费时间")),
            new SentimentLevel(5, "愤怒", "共情并建议人工协助", List.of(
                    "我要投诉", "太差了", "垃圾服务", "你们是骗子",
                    "太过分", "无法容忍", "告你们",
                    "你们等着", "没完", "别想糊弄", "什么玩意",
                    "气死我了", "烦死了", "什么破玩意", "滚"))
    );

    /** 关键词→等级映射表 */
    private final Map<String, Integer> keywordMap = new ConcurrentHashMap<>();

    /** LLM 调用接口（可选，复杂情绪用 LLM 识别） */
    private final Function<String, Integer> llmAnalyzer;

    /** ⭐ BGE 语义情感分析器（可选，处理语义相似但字面不同的表达） */
    private final BgeSentimentAnalyzer bgeAnalyzer;


    public SentimentAnalysisService() {
        this(null, null);
    }

    public SentimentAnalysisService(Function<String, Integer> llmAnalyzer) {
        this(llmAnalyzer, null);
    }

    @Autowired
    public SentimentAnalysisService(
            @Autowired(required = false) Function<String, Integer> llmAnalyzer,
            @Autowired(required = false) BgeEmbeddingModel embeddingModel) {
        this.llmAnalyzer = llmAnalyzer;
        this.bgeAnalyzer = embeddingModel != null ? new BgeSentimentAnalyzer(embeddingModel) : null;
        buildKeywordMap();
    }

    private void buildKeywordMap() {
        for (SentimentLevel sl : SENTIMENT_LEVELS) {
            for (String kw : sl.keywords) {
                keywordMap.put(kw, sl.level);
            }
        }
    }

    /**
     * 分析用户输入的情感。
     *
     * @param userInput 用户输入文本
     * @return 情感分析结果
     */
    public SentimentResult analyze(String userInput) {
        return analyze(userInput, null);
    }

    /**
     * 无状态分析；跨轮趋势由统一预处理中的 Redis 快照维护。
     *
     * @param userInput 用户输入文本
     * @param sessionId 兼容旧调用签名，不在分析器中存储会话状态
     * @return 情感分析结果
     */
    public SentimentResult analyze(String userInput, String sessionId) {
        if (userInput == null || userInput.isBlank()) {
            return new SentimentResult(2, "中性", "正常回复", false, false, 0);
        }

        // Step 1: 关键词快速匹配
        int level = keywordMatch(userInput);
        int confidence = level > 0 ? 95 : 0;

        // Step 2: 关键词未命中或结果模糊时，用 BGE 语义分析
        // BGE 能处理"语义相似但字面不同"的表达，例如"效率低下"→负面
        if (level == 0 && bgeAnalyzer != null) {
            int bgeLevel = bgeAnalyzer.analyze(userInput);
            if (bgeLevel > 0) {
                log.debug("[Sentiment] BGE 语义匹配: level={}", bgeLevel);
                level = bgeLevel;
                confidence = 85;
            }
        }

        // Step 3: 仍无法确定且 LLM 可用时，用 LLM 识别复杂情绪
        if (level == 0 && llmAnalyzer != null) {
            try {
                level = llmAnalyzer.apply(userInput);
                if (level >= 1 && level <= 5) {
                    confidence = 80;
                }
            } catch (Exception e) {
                throw new IllegalStateException("Sentiment inference failed", e);
            }
        }

        // 未识别到任何情绪 → 默认为中性
        if (level < 1 || level > 5) {
            level = 2;
            confidence = 50;
        }

        SentimentLevel sl = getByLevel(level);
        // A complaint or negative emotion alone must not stop order/product assistance.
        boolean needHandoff = userInput.matches("(?s).*(?:请转人工|我要人工|找人工客服|转接人工客服).*")
                && !userInput.matches("(?s).*(?:不要|不用|不想|无需).{0,4}人工.*");
        return new SentimentResult(level, sl.name,
                needHandoff ? "建议人工协助" : level >= 3 ? "共情并继续解决业务问题" : sl.responseStrategy,
                needHandoff, false, confidence);
    }

    /**
     * 关键词匹配（快速路径）。
     */
    private int keywordMatch(String input) {
        int maxLevel = 0;
        boolean[] matched = new boolean[input.length()];
        var entries = keywordMap.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getKey().length()).reversed())
                .toList();
        for (var entry : entries) {
            for (int index = input.indexOf(entry.getKey()); index >= 0; index = input.indexOf(entry.getKey(), index + 1)) {
                int end = index + entry.getKey().length();
                boolean overlaps = false;
                for (int i = index; i < end; i++) if (matched[i]) overlaps = true;
                if (overlaps) continue;
                String before = input.substring(Math.max(0, index - 3), index);
                if (before.matches(".*(?:不|没|无|不是|并非|不要|不用)$")) continue;
                java.util.Arrays.fill(matched, index, end, true);
                maxLevel = Math.max(maxLevel, entry.getValue());
            }
        }
        return maxLevel;
    }

    /**
     * 根据情感等级调整回复前缀。
     *
     * @param level 情感等级
     * @return 语气调整前缀（空字符串表示无需调整）
     */
    public String getTonePrefix(int level) {
        return switch (level) {
            case 3 -> "抱歉给您带来不便。";
            case 4 -> "非常抱歉，我立即为您处理。";
            case 5 -> "非常抱歉给您带来不好的体验。可以联系人工客服进一步协助。";
            default -> "";
        };
    }

    /**
     * 构建需要转人工时的完整回复，确保转接提示只出现一次。
     */
    public String getHandoffResponse(int level) {
        if (level >= 5) {
            return "非常抱歉给您带来不好的体验。可以联系人工客服进一步协助。";
        }
        return getTonePrefix(level) + "可以联系人工客服进一步协助。";
    }

    private SentimentLevel getByLevel(int level) {
        for (SentimentLevel sl : SENTIMENT_LEVELS) {
            if (sl.level == level) return sl;
        }
        return SENTIMENT_LEVELS.get(1); // 默认中性
    }

    /** 情感分析结果 */
    public record SentimentResult(
            /** 情感等级 1~5 */
            int level,
            /** 情感名称 */
            String name,
            /** 建议的处理策略 */
            String responseStrategy,
            /** 是否明确请求人工协助（不是已经转接成功） */
            boolean needHandoff,
            /** 是否触发情绪升级预警 */
            boolean escalated,
            /** 分析置信度（0~100） */
            int confidence
    ) {
        /** 保留原有构造签名，避免已有调用方因新增置信度字段而失效。 */
        public SentimentResult(int level, String name, String responseStrategy,
                               boolean needHandoff, boolean escalated) {
            this(level, name, responseStrategy, needHandoff, escalated, 0);
        }
    }

    /** 情感等级定义 */
    private record SentimentLevel(int level, String name, String responseStrategy, List<String> keywords) {}
}
