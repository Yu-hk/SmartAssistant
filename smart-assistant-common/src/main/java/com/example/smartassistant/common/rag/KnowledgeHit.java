/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

/**
 * 检索结果——知识库单次匹配的结果，含相关度评分和文档。
 * <p>
 * {@link #toContext()} 输出的格式包含稳定引用 ID {@code [CID:doc-id]}，
 * LLM 可在答案中引用此 ID，实现答案结论到来源的追溯。
 * </p>
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

    /**
     * 格式化为 LLM 可读的上下文片段（含稳定引用 ID）。
     * <p>
     * 输出格式：
     * <pre>
     * [CID:doc-001] [版本: v2] [来源: URL]
     * 【标题】（相关度: 95%）
     * 正文内容...
     * </pre>
     * </p>
     */
    public String toContext() {
        StringBuilder sb = new StringBuilder();

        // ⭐ 稳定引用 ID：[CID:doc-001]，供 LLM 在答案中引用
        sb.append("[CID:").append(document.getId()).append("]");

        // 版本信息（LLM 判断时效性的依据）
        String version = document.getVersion();
        if (version != null && !version.isBlank() && !"v1".equals(version)) {
            sb.append(" [版本: ").append(version).append("]");
        }

        // 来源信息
        String sourceUrl = document.getSourceUrl();
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            sb.append(" [来源: ").append(sourceUrl).append("]");
        }

        // 租户信息（调试用途）
        String tenantId = document.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            sb.append(" [租户: ").append(tenantId).append("]");
        }

        // 过期警告
        if (document.getExpireAt() > 0) {
            long daysLeft = (document.getExpireAt() - System.currentTimeMillis()) / 86400000;
            if (daysLeft > 0 && daysLeft < 30) {
                sb.append(" [⚠️ 距过期还有 ").append(daysLeft).append(" 天]");
            } else if (daysLeft <= 0) {
                sb.append(" [⚠️ 已过期]");
            }
        }

        String meta = sb.toString();
        return "【" + document.getTitle() + "】（相关度: "
                + String.format("%.0f%%", score * 100) + "）" + meta
                + "\n" + document.getContent() + "\n";
    }

    @Override
    public String toString() {
        return "KnowledgeHit{title='" + document.getTitle()
                + "', score=" + String.format("%.2f", score) + "}";
    }
}
