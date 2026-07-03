/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.sentiment;

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
 * 等级≥4触发转人工、情绪追踪。
 * </p>
 *
 * <h3>情感等级</h3>
 * <table>
 *   <tr><th>等级</th><th>名称</th><th>处理策略</th></tr>
 *   <tr><td>1</td><td>正面</td><td>礼貌感谢</td></tr>
 *   <tr><td>2</td><td>中性</td><td>正常回复</td></tr>
 *   <tr><td>3</td><td>轻微负面</td><td>道歉+改进建议</td></tr>
 *   <tr><td>4</td><td>负面</td><td>道歉+加快处理</td></tr>
 *   <tr><td>5</td><td>愤怒</td><td>立即转人工</td></tr>
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
                    "有点慢", "不太方便", "一般", "还行吧", "不怎么好",
                    "有点麻烦", "不太满意", "能不能快点", "等很久了",
                    "一般般", "凑合", "马马虎虎", "不怎么样", "不太好",
                    "有点失望", "不够好", "不算好", "不太行")),
            new SentimentLevel(4, "负面", "道歉+加快处理", List.of(
                    "太慢了", "等了很久", "不满意", "差劲", "太差",
                    "非常失望", "太糟糕", "服务差", "效率低", "太离谱",
                    "受不了", "忍不了", "搞什么", "怎么回事", "太差劲",
                    "太让人失望", "很不满意", "体验很差", "浪费时间")),
            new SentimentLevel(5, "愤怒", "立即转人工", List.of(
                    "投诉", "太差了", "垃圾", "骗子", "赔偿", "举报",
                    "太过分", "无法容忍", "欺诈", "告你", "曝光",
                    "律师函", "315", "消费者协会", "退款赔偿",
                    "你们等着", "没完", "别想糊弄", "什么玩意",
                    "气死我了", "烦死了", "什么破玩意", "滚"))
    );

    /** 关键词→等级映射表 */
    private final Map<String, Integer> keywordMap = new ConcurrentHashMap<>();

    /** LLM 调用接口（可选，复杂情绪用 LLM 识别） */
    private final Function<String, Integer> llmAnalyzer;

    /** ⭐ BGE 语义情感分析器（可选，处理语义相似但字面不同的表达） */
    private final BgeSentimentAnalyzer bgeAnalyzer;

    /** 跨轮次情绪追踪（sessionId → 最近5轮情绪等级） */
    private final Map<String, int[]> emotionHistory = new ConcurrentHashMap<>();

    /** 情绪升级阈值：连续 N 轮情绪等级≥3 时触发预警 */
    private static final int ESCALATION_WINDOW = 3;

    /** 情绪升级警告等级 */
    private static final int ESCALATION_WARN_LEVEL = 3;

    public SentimentAnalysisService() {
        this(null, null);
    }

    public SentimentAnalysisService(Function<String, Integer> llmAnalyzer) {
        this(llmAnalyzer, null);
    }

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
     * 分析用户输入的情感（含跨轮次情绪追踪）。
     *
     * @param userInput 用户输入文本
     * @param sessionId 会话 ID（用于情绪追踪，可为 null）
     * @return 情感分析结果
     */
    public SentimentResult analyze(String userInput, String sessionId) {
        if (userInput == null || userInput.isBlank()) {
            return new SentimentResult(2, "中性", "正常回复", false, false);
        }

        // Step 1: 关键词快速匹配
        int level = keywordMatch(userInput);

        // Step 2: 关键词未命中或结果模糊时，用 BGE 语义分析
        // BGE 能处理"语义相似但字面不同"的表达，例如"效率低下"→负面
        if (level == 0 && bgeAnalyzer != null) {
            int bgeLevel = bgeAnalyzer.analyze(userInput);
            if (bgeLevel > 0) {
                log.debug("[Sentiment] BGE 语义匹配: level={}", bgeLevel);
                level = bgeLevel;
            }
        }

        // Step 3: 仍无法确定且 LLM 可用时，用 LLM 识别复杂情绪
        if (level == 0 && llmAnalyzer != null) {
            try {
                level = llmAnalyzer.apply(userInput);
            } catch (Exception e) {
                log.warn("[Sentiment] LLM 分析失败，使用关键词结果: {}", e.getMessage());
            }
        }

        // 未识别到任何情绪 → 默认为中性
        if (level < 1 || level > 5) level = 2;

        // Step 3: 情绪追踪
        boolean escalated = false;
        if (sessionId != null) {
            escalated = trackEmotion(sessionId, level);
        }

        SentimentLevel sl = getByLevel(level);
        boolean needHandoff = level >= 4;

        return new SentimentResult(level, sl.name, sl.responseStrategy, needHandoff, escalated);
    }

    /**
     * 关键词匹配（快速路径）。
     */
    private int keywordMatch(String input) {
        int maxLevel = 0;
        for (Map.Entry<String, Integer> entry : keywordMap.entrySet()) {
            if (input.contains(entry.getKey())) {
                maxLevel = Math.max(maxLevel, entry.getValue());
            }
        }
        return maxLevel;
    }

    /**
     * 跨轮次情绪追踪——检测情绪升级趋势。
     *
     * @param sessionId 会话 ID
     * @param level     当前情绪等级
     * @return 是否触发情绪升级预警
     */
    private boolean trackEmotion(String sessionId, int level) {
        int[] history = emotionHistory.computeIfAbsent(sessionId, k -> new int[5]);
        // 将历史左移
        System.arraycopy(history, 1, history, 0, history.length - 1);
        history[history.length - 1] = level;

        // 检查是否连续 N 轮情绪等级≥3
        if (history.length >= ESCALATION_WINDOW) {
            int count = 0;
            for (int i = history.length - ESCALATION_WINDOW; i < history.length; i++) {
                if (history[i] >= ESCALATION_WARN_LEVEL) count++;
            }
            if (count >= ESCALATION_WINDOW) {
                log.warn("[Sentiment] ⚠️ 情绪升级预警: session={}, history={}", sessionId, history);
                return true;
            }
        }
        return false;
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
            case 5 -> "非常抱歉给您带来不好的体验。正在为您转接人工客服。";
            default -> "";
        };
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
            /** 是否需转人工（等级≥4） */
            boolean needHandoff,
            /** 是否触发情绪升级预警 */
            boolean escalated
    ) {}

    /** 情感等级定义 */
    private record SentimentLevel(int level, String name, String responseStrategy, List<String> keywords) {}
}
