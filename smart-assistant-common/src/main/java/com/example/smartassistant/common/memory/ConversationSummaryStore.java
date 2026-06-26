/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import java.util.List;

/**
 * 对话摘要持久化存储接口 — 方案 A：压缩摘要写入向量数据库。
 * <p>
 * 在 {@code SmartReActAgent.compressHistory()} 每次生成摘要后异步调用，
 * 将 9 段式结构摘要嵌入为向量并存储到持久化存储中。
 * </p>
 *
 * <p>实现类应处理：向量嵌入 + 存储 + 语义检索 + 异常静默降级。</p>
 *
 * @see SmartReActAgent#withSummaryStore(ConversationSummaryStore)
 */
@FunctionalInterface
public interface ConversationSummaryStore {

    /**
     * 存储对话摘要（异步安全、应异常-safe）。
     *
     * @param userId      用户 ID（可为空字符串）
     * @param sessionId   会话 ID（可为空字符串）
     * @param agentName   Agent 名称
     * @param summary     9 段式结构摘要文本
     * @param generation  摘要代数（递增）
     */
    void store(String userId, String sessionId, String agentName,
               String summary, int generation);

    /**
     * 检索与查询语义最相关的历史摘要。
     *
     * @param userId 用户 ID
     * @param query  查询文本
     * @param topK   返回条数
     * @return 相关摘要列表，降序排列
     */
    default List<SummaryHit> search(String userId, String query, int topK) {
        return List.of();
    }

    /** 检索结果 */
    final class SummaryHit {
        private final String summary;
        private final double score;
        private final int generation;
        private final long createdAt;

        public SummaryHit(String summary, double score, int generation, long createdAt) {
            this.summary = summary;
            this.score = score;
            this.generation = generation;
            this.createdAt = createdAt;
        }

        public String getSummary() { return summary; }
        public double getScore() { return score; }
        public int getGeneration() { return generation; }
        public long getCreatedAt() { return createdAt; }
    }
}
