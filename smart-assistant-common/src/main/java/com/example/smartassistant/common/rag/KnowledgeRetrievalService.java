/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识检索服务——统一管理多个知识库的检索入口。
 * <p>
 * 支持按名称检索特定知识库，或跨库混合检索。
 * 结果格式化为 LLM 友好的上下文片段。
 * </p>
 */
public class KnowledgeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    private final Map<String, KnowledgeBase> bases = new ConcurrentHashMap<>();

    /** 默认 Top-K */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 注册知识库。
     */
    public KnowledgeRetrievalService register(KnowledgeBase kb) {
        bases.put(kb.getName(), kb);
        log.info("[KnowledgeRetrieval] 注册知识库: name={}, docs={}", kb.getName(), kb.size());
        return this;
    }

    /**
     * 从指定知识库中检索。
     *
     * @param kbName 知识库名称
     * @param query  检索查询
     * @param topK   返回条数
     * @return 格式化的上下文字符串
     */
    public String search(String kbName, String query, int topK) {
        return search(kbName, query, topK, KnowledgeBase.PUBLIC_TENANT);
    }

    /**
     * 从指定知识库中检索（带租户隔离）。
     *
     * @param kbName   知识库名称
     * @param query    检索查询
     * @param topK     返回条数
     * @param tenantId 租户 ID（空字符串表示查询公开文档）
     * @return 格式化的上下文字符串
     */
    public String search(String kbName, String query, int topK, String tenantId) {
        return search(kbName, query, topK, AclContext.forTenant(tenantId));
    }

    /**
     * 从指定知识库中检索（按细粒度 ACL 上下文）。
     *
     * @param kbName 知识库名称
     * @param query  检索查询
     * @param topK   返回条数
     * @param acl    访问控制上下文（含 tenantId / userId / roles / securityClearance）
     * @return 格式化的上下文字符串
     */
    public String search(String kbName, String query, int topK, AclContext acl) {
        KnowledgeBase kb = bases.get(kbName);
        if (kb == null) {
            return "知识库 '" + kbName + "' 不存在。";
        }
        List<KnowledgeHit> hits = kb.search(query, topK > 0 ? topK : DEFAULT_TOP_K, acl);
        if (hits.isEmpty()) {
            return "INSUFFICIENT_EVIDENCE: 知识库 '" + kbName + "' 中未找到与 '" + query + "' 相关的信息。";
        }
        return formatResults(kbName, hits);
    }

    /**
     * 跨所有知识库检索（带租户隔离）。
     */
    public String searchAll(String query, int topK, String tenantId) {
        return searchAll(query, topK, AclContext.forTenant(tenantId));
    }

    /**
     * 跨所有知识库检索（按细粒度 ACL 上下文）。
     */
    public String searchAll(String query, int topK, AclContext acl) {
        List<KnowledgeHit> allHits = new ArrayList<>();
        for (var kb : bases.values()) {
            allHits.addAll(kb.search(query, topK > 0 ? topK : DEFAULT_TOP_K, acl));
        }
        allHits.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        if (allHits.isEmpty()) {
            return "INSUFFICIENT_EVIDENCE: 所有知识库中均未找到与 '" + query + "' 相关的信息。";
        }
        return formatResults("全部知识库", allHits.subList(0, Math.min(topK, allHits.size())));
    }

    /**
     * 跨所有知识库检索（旧兼容签名）。
     */
    public String searchAll(String query, int topK) {
        return searchAll(query, topK, KnowledgeBase.PUBLIC_TENANT);
    }

    /** 获取知识库中文档数 */
    public int getDocCount(String kbName) {
        KnowledgeBase kb = bases.get(kbName);
        return kb != null ? kb.size() : 0;
    }

    /** 获取所有知识库名称 */
    public Set<String> getKnowledgeBaseNames() {
        return bases.keySet();
    }

    /** 重新索引指定知识库 */
    public void reindex(String kbName) {
        KnowledgeBase kb = bases.get(kbName);
        if (kb != null) kb.reindex();
    }

    // ==================== 结果格式化 ====================

    private String formatResults(String kbName, List<KnowledgeHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("📚 知识库「").append(kbName).append("」查询结果：\n\n");
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeHit hit = hits.get(i);
            sb.append(i + 1).append(". ").append(hit.toContext()).append("\n");
        }
        return sb.toString().trim();
    }
}
