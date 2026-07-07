/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.AuthorityLevel;
import com.example.smartassistant.common.rag.KnowledgeDocument;

/**
 * 入库前 Chunk 质量评分器——对标字节 RAG 七连问第二问「清洗 pipeline·质量评分」。
 * <p>
 * 不是所有清洗完的 chunk 都值得入库。给每个 chunk 打 0~1 质量分：
 * <ul>
 *   <li>信息密度（实词占比）：非空白非标点字符 / 总长度，低于阈值视为页眉页脚碎片</li>
 *   <li>长度门槛：过短（&lt; {@code MIN_LENGTH}）直接低分</li>
 *   <li>来源权威性：{@link AuthorityLevel#getRank()} 加权</li>
 * </ul>
 * 低于阈值（默认 0.3）的 chunk 不入库，宁可少一点，不要烂的污染检索。
 * </p>
 */
public class ChunkQualityScorer {

    /** 最短长度门槛（低于视为页眉页脚碎片） */
    private static final int MIN_LENGTH = 50;

    /** 信息密度阈值（实词占比达到此值即满分） */
    private static final double DENSITY_THRESHOLD = 0.30;

    /** 默认合格阈值 */
    private final double threshold;

    public ChunkQualityScorer() {
        this(0.30);
    }

    public ChunkQualityScorer(double threshold) {
        this.threshold = threshold;
    }

    /**
     * 计算质量分（0~1）。
     *
     * @param doc 知识文档（按 content 评分）
     * @return 质量分；null/空内容返回 0
     */
    public double score(KnowledgeDocument doc) {
        if (doc == null || doc.getContent() == null || doc.getContent().isBlank()) {
            return 0.0;
        }
        String content = doc.getContent();
        int len = content.length();
        if (len < MIN_LENGTH) {
            return 0.1;
        }

        // 信息密度：非空白非标点字符占比
        long meaningful = content.chars()
                .filter(ch -> !Character.isWhitespace(ch) && !isPunctuation(ch))
                .count();
        double density = (double) meaningful / len;
        double densityScore = clamp(density / DENSITY_THRESHOLD, 0.0, 1.0);

        // 来源权威性加权（rank 4→1.0, 3→0.75, 2→0.5, 1→0.25）
        double authorityScore = doc.getAuthorityLevel() != null
                ? doc.getAuthorityLevel().getRank() / 4.0
                : 0.75;

        // 综合：密度为主（0.7），权威性为辅（0.3）
        return clamp(densityScore * 0.7 + authorityScore * 0.3, 0.0, 1.0);
    }

    /** 是否达到合格阈值 */
    public boolean isQualified(KnowledgeDocument doc) {
        return score(doc) >= threshold;
    }

    /** 是否达到指定阈值 */
    public boolean isQualified(KnowledgeDocument doc, double customThreshold) {
        return score(doc) >= customThreshold;
    }

    /** 判断是否为标点符号（含中文标点） */
    private static boolean isPunctuation(int ch) {
        if (Character.isISOControl(ch)) return true;
        // ASCII 标点
        if (ch < 128 && !Character.isLetterOrDigit(ch)) return true;
        // 常见中文标点
        switch (ch) {
            case '，': case '。': case '、': case '；': case '：':
            case '“': case '”': case '（': case '）': case '【': case '】':
            case '《': case '》': case '·': case '—': case '…':
            case '！': case '？': case '．': case '　':
                return true;
            default:
                return false;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
