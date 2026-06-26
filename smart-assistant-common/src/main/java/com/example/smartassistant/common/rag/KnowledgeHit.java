/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

/**
 * 检索结果——知识库单次匹配的结果，含相关度评分和文档。
 */
public class KnowledgeHit {

    private final KnowledgeDocument document;
    private final double score;

    public KnowledgeHit(KnowledgeDocument document, double score) {
        this.document = document;
        this.score = score;
    }

    public KnowledgeDocument getDocument() { return document; }
    public double getScore() { return score; }

    /** 格式化为 LLM 可读的上下文片段 */
    public String toContext() {
        String validity = "";
        if (document.getExpireAt() > 0) {
            long daysLeft = (document.getExpireAt() - System.currentTimeMillis()) / 86400000;
            if (daysLeft > 0 && daysLeft < 30) {
                validity = " [⚠️ 距过期还有 " + daysLeft + " 天]";
            } else if (daysLeft <= 0) {
                validity = " [⚠️ 已过期]";
            }
        }
        return "【" + document.getTitle() + "】（相关度: "
                + String.format("%.0f%%", score * 100) + "）" + validity
                + "\n" + document.getContent() + "\n";
    }

    @Override
    public String toString() {
        return "KnowledgeHit{title='" + document.getTitle()
                + "', score=" + String.format("%.2f", score) + "}";
    }
}
