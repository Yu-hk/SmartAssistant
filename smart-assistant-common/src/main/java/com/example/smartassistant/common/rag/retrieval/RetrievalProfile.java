/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.retrieval;

/**
 * 检索强度配置文件——控制 RAG 检索的路径数量和资源消耗。
 * <p>
 * 参考 RAG 文章的生产建议：
 * "生产系统要做分级策略：普通问题走轻量链路，高风险问题走强链路。"
 * </p>
 */
public enum RetrievalProfile {

    /**
     * 轻量检索：单路或双路快速召回。
     * 适用场景：闲聊、简单事实查询、重复性高频问题。
     * 路径数：1~2 路，不启用 RRF 融合，不启用 Rerank。
     */
    LIGHT(2, false, false, 2000),

    /**
     * 标准检索：多路召回 + RRF 融合。
     * 适用场景：大多数业务问题。
     * 路径数：3 路，启用 RRF，不启用 Rerank。
     */
    STANDARD(3, true, false, 5000),

    /**
     * 深度检索：全路径召回 + RRF + 可选 Rerank。
     * 适用场景：退款纠纷、合同解读、法律条款等高价值/高风险查询。
     * 路径数：5 路（全部），启用 RRF，启用 Rerank。
     */
    DEEP(5, true, true, 10000);

    /** 最大检索路径数 */
    private final int maxPaths;

    /** 是否启用 RRF 融合 */
    private final boolean rrfEnabled;

    /** 是否启用 Cross-Encoder Rerank */
    private final boolean rerankEnabled;

    /** 最大响应时间预算（毫秒） */
    private final long timeBudgetMs;

    RetrievalProfile(int maxPaths, boolean rrfEnabled, boolean rerankEnabled, long timeBudgetMs) {
        this.maxPaths = maxPaths;
        this.rrfEnabled = rrfEnabled;
        this.rerankEnabled = rerankEnabled;
        this.timeBudgetMs = timeBudgetMs;
    }

    public int getMaxPaths() { return maxPaths; }
    public boolean isRrfEnabled() { return rrfEnabled; }
    public boolean isRerankEnabled() { return rerankEnabled; }
    public long getTimeBudgetMs() { return timeBudgetMs; }

    /**
     * 获取 Top-K 结果数（所有 profile 相同，但深度检索 Rerank 后可能保留更多）。
     */
    public int getTopK() {
        return this == DEEP ? 10 : 5;
    }
}
