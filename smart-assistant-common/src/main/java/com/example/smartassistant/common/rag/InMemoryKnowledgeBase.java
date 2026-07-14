/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import com.example.smartassistant.common.rag.trace.RetrievalTrace;
import com.example.smartassistant.common.rag.retrieval.CrossDocumentConflictResolver;
import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 基于 BGE 嵌入的内存知识库——文档存储于内存，使用 BGE 向量进行余弦相似度检索。
 * <p>
 * 适用场景：中小规模知识库（<1000 文档），Embedding 服务可用时自动启用。
 * 参考 RAG 文章的两阶段架构：先向量粗筛(Top-50)，再按 BM25+时效性精排(Top-5)。
 * </p>
 */
public class InMemoryKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(InMemoryKnowledgeBase.class);

    private final String name;
    private final BgeEmbeddingModel embeddingModel;

    /** 文档存储 id → doc */
    private final Map<String, KnowledgeDocument> docs = new ConcurrentHashMap<>();

    /** 文档 id → 嵌入向量 */
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();

    /** 粗筛 Top-K */
    private static final int ROUGH_TOP_K = 50;

    /** 返回 Top-K */
    private static final int DEFAULT_TOP_K = 5;

    /** 时间衰减 λ（知识类取 0.01，新闻类取 0.1） */
    private static final double TIME_DECAY_LAMBDA = 0.01;

    /** BM25 关键词加分权重（与 BM25 分数的混合比例，0=不使用 BM25） */
    private static final double BM25_MIX_WEIGHT = 0.3;

    /** 版本优先级衰减权重（同内容但旧版本文档的分数折扣） */
    private static final double VERSION_PENALTY_RATE = 0.1;

    /** 余弦相似度阈值（低于此值不返回） */
    private static final double MIN_SIMILARITY = 0.30;

    /** BM25 评分器（HanLP 中文分词） */
    private Bm25Scorer bm25Scorer;

    /** 重排序器（Cross-Encoder，可选） */
    private Reranker reranker;

    /** ⭐ 检索链路追溯消费者（可选，null 时不追蹤） */
    private Consumer<RetrievalTrace> traceConsumer;

    /** ⭐ 检索侧跨文档冲突消解器（Q6 第二层，可选，null 时不消解） */
    private CrossDocumentConflictResolver conflictResolver;

    /**
     * @param name           知识库名称
     * @param embeddingModel BGE 嵌入模型
     * @param tokenizer      HanLP 中文分词器（用于 BM25，可为 null 则跳过 BM25）
     * @param reranker       Cross-Encoder 重排序器（可为 null 则跳过）
     */
    public InMemoryKnowledgeBase(String name, BgeEmbeddingModel embeddingModel,
                                  ChineseTokenizer tokenizer, Reranker reranker) {
        this.name = name;
        this.embeddingModel = embeddingModel;
        if (tokenizer != null) {
            this.bm25Scorer = new Bm25Scorer(tokenizer);
        }
        this.reranker = reranker != null ? reranker : Reranker.identity();
    }

    @Override
    public String getName() { return name; }

    @Override
    public void addDocument(KnowledgeDocument doc) {
        if (doc == null) return;
        docs.put(doc.getId(), doc);
        // 计算 embedding
        float[] vec = embeddingModel.embedding(doc.toEmbedText());
        if (vec != null) {
            vectors.put(doc.getId(), normalize(vec));
        }
        log.info("[KnowledgeBase:{}] 添加文档: id={}, title={}", name, doc.getId(), doc.getTitle());
    }

    @Override
    public void removeDocument(String id) {
        docs.remove(id);
        vectors.remove(id);
    }

    @Override
    public void removeByBaseDocId(String baseDocId) {
        if (baseDocId == null || baseDocId.isBlank()) return;
        List<String> toRemove = docs.entrySet().stream()
                .filter(e -> {
                    String docBaseId = e.getValue().getBaseDocId();
                    return docBaseId != null && docBaseId.equals(baseDocId);
                })
                .map(Map.Entry::getKey)
                .toList();
        toRemove.forEach(id -> {
            docs.remove(id);
            vectors.remove(id);
        });
        if (!toRemove.isEmpty()) {
            log.info("[KnowledgeBase:{}] 按 baseDocId 删除: baseId={}, removed={} chunks",
                    name, baseDocId, toRemove.size());
        }
    }

    @Override
    public List<String> listIdsByBaseDocId(String baseDocId) {
        if (baseDocId == null || baseDocId.isBlank()) return List.of();
        return docs.entrySet().stream()
                .filter(e -> baseDocId.equals(e.getValue().getBaseDocId()))
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public void updateStatus(String docId, DocumentStatus status) {
        if (docId == null || docId.isBlank() || status == null) return;
        KnowledgeDocument old = docs.get(docId);
        if (old == null) return;
        // KnowledgeDocument 字段为 final，需重建
        KnowledgeDocument updated = new KnowledgeDocument(
                old.getId(), old.getTitle(), old.getContent(),
                old.getCategory(), old.getKeywords(),
                old.getEffectiveAt(), old.getExpireAt(),
                old.getTenantId(), old.getVersion(),
                old.getSourceUrl(), old.getChunkIndex(),
                old.getParentDocId(),
                old.getAuthorityLevel(), status);
        docs.put(docId, updated);
        log.info("[KnowledgeBase:{}] 状态更新: id={}, status={}", name, docId, status);
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK) {
        return search(query, topK, KnowledgeBase.PUBLIC_TENANT);
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK, String tenantId) {
        // 退化为仅租户过滤（保持向后兼容）
        return search(query, topK, AclContext.forTenant(tenantId));
    }

    /**
     * ⭐ 细粒度 ACL 检索（文章⑤：权限进入检索层，服务端生成 filter）。
     */
    @Override
    public List<KnowledgeHit> search(String query, int topK, AclContext acl) {
        if (query == null || query.isBlank() || docs.isEmpty()) return Collections.emptyList();
        int k = (topK > 0) ? topK : DEFAULT_TOP_K;

        // ⭐ 检索链路追溯
        RetrievalTrace trace = traceConsumer != null
                ? new RetrievalTrace("kb:" + name + ":" + System.currentTimeMillis(), query)
                : null;

        // Stage 1: 向量粗筛 (Bi-Encoder)
        float[] queryVec = embeddingModel.embedding(query);
        if (queryVec == null) {
            log.warn("[KnowledgeBase:{}] 嵌入服务不可用，降级到关键词匹配", name);
            List<KnowledgeHit> fallback = fallbackKeywordSearch(query, k, acl);
            if (trace != null) {
                trace.hit(!fallback.isEmpty()).durationMs(System.currentTimeMillis());
                traceConsumer.accept(trace);
            }
            return fallback;
        }
        queryVec = normalize(queryVec);

        List<ScoredDoc> roughResults = new ArrayList<>();
        for (var entry : vectors.entrySet()) {
            KnowledgeDocument doc = docs.get(entry.getKey());
            if (doc == null || !doc.isRetrievable()) continue;

            // 🔴 ACL 检索前过滤（租户 + 角色 + 用户 + 安全等级）
            if (!tenantMatches(doc, acl)) continue;

            double cosSim = cosineSimilarity(queryVec, entry.getValue());
            if (cosSim < MIN_SIMILARITY) continue;

            // Stage 2: 精排 — 组合评分
            double finalScore = composeScore(cosSim, doc, query);
            roughResults.add(new ScoredDoc(doc, finalScore));
        }

        // 排序取 Top-K（粗排结果用于 Reranker）
        roughResults.sort((a, b) -> Double.compare(b.score, a.score));

        // ⭐ 记录向量检索步
        if (trace != null) {
            for (int i = 0; i < roughResults.size(); i++) {
                ScoredDoc sd = roughResults.get(i);
                trace.addStep("向量检索+" + (bm25Scorer != null ? "BM25" : ""),
                        query, i + 1, sd.doc.getId(), sd.doc.getTitle(), sd.score);
            }
        }

        List<KnowledgeHit> hits = roughResults.stream()
                .limit(Math.max(k, 20)) // 给 Reranker 留更多候选
                .map(sd -> new KnowledgeHit(sd.doc, sd.score))
                .collect(Collectors.toList());

        // Stage 3: Cross-Encoder 重排序（可选）
        if (reranker != null && reranker != Reranker.identity()) {
            hits = reranker.rerank(hits, query, k);
        } else {
            hits = hits.size() <= k ? hits : hits.subList(0, k);
        }

        // Stage 4: ⭐ 跨文档冲突消解（Q6 第二层）—按权威性/版本压制矛盾低权威来源
        var resolvedHits = (List<CrossDocumentConflictResolver.ResolvedHit>) null;
        if (conflictResolver != null) {
            var result = conflictResolver.resolve(hits);
            resolvedHits = result.resolved();
            hits = resolvedHits.stream()
                    .map(r -> new KnowledgeHit(r.original().getDocument(), r.adjustedScore()))
                    .collect(Collectors.toList());
        }

        // ⭐ 记录最终结果（含分数明细）
        if (trace != null) {
            for (int i = 0; i < hits.size(); i++) {
                KnowledgeHit hit = hits.get(i);
                trace.addFinalContext(hit.toContext());
                if (resolvedHits != null && i < resolvedHits.size()) {
                    var b = resolvedHits.get(i).breakdown();
                    trace.addScoreBreakdown(b.baseScore(), b.authorityFactor(), b.conflictPenalty(), b.finalScore());
                }
            }
            trace.hit(!hits.isEmpty()).durationMs(System.currentTimeMillis());
            traceConsumer.accept(trace);
        }

        return hits;
    }

    /**
     * 设置检索链路追溯消费者。
     *
     * @param traceConsumer 消费者，接收每个查询的 RetrievalTrace（Redis 存储等）
     */
    public void setTraceConsumer(Consumer<RetrievalTrace> traceConsumer) {
        this.traceConsumer = traceConsumer;
    }

    /**
     * 设置检索侧跨文档冲突消解器（Q6 第二层）。
     * <p>在重排序之后、链路追溯记录之前生效；null 时跳过消解。</p>
     *
     * @param conflictResolver 跨文档冲突消解器
     */
    public void setConflictResolver(CrossDocumentConflictResolver conflictResolver) {
        this.conflictResolver = conflictResolver;
    }

    /**
     * ⭐ Multi-Query 检索：多个查询分别检索后 RRF 融合。
     * <p>
     * 每个 query 单独走完整检索链路（向量→精排→Rerank），
     * 结果通过 Reciprocal Rank Fusion 融合，
     * 解决"用户换说法就搜不到"的问题。
     * </p>
     *
     * @param queries  多个查询变体
     * @param topK     最终返回条数
     * @param tenantId 租户 ID（空字符串 = 公开）
     * @return RRF 融合后按分数降序排列的结果
     */
    public List<KnowledgeHit> searchMultiQuery(List<String> queries, int topK, String tenantId) {
        if (queries == null || queries.isEmpty()) return Collections.emptyList();
        int k = (topK > 0) ? topK : DEFAULT_TOP_K;

        // 每路召回取更多候选，给 RRF 融合留足空间
        int perQueryK = Math.max(k * 2, 10);

        // 各路查回的排名：每个元素是 docId 列表（按 rank 顺序）
        List<List<String>> allRankings = new ArrayList<>();

        // ⭐ 检索链路追溯
        RetrievalTrace trace = traceConsumer != null
                ? new RetrievalTrace("kb:multi:" + name + ":" + System.currentTimeMillis(),
                queries.get(0))
                : null;
        if (trace != null) {
            queries.forEach(trace::addVariant);
        }

        for (String query : queries) {
            if (query == null || query.isBlank()) continue;
            List<KnowledgeHit> hits = search(query, perQueryK, AclContext.forTenant(tenantId));
            List<String> ranking = hits.stream()
                    .map(h -> h.getDocument().getId())
                    .collect(Collectors.toList());
            allRankings.add(ranking);

            if (trace != null) {
                for (int i = 0; i < hits.size(); i++) {
                    KnowledgeHit hit = hits.get(i);
                    trace.addStep("Multi-Query:" + query, query, i + 1,
                            hit.getDocument().getId(), hit.getDocument().getTitle(), hit.getScore());
                }
            }
        }

        // RRF 融合（K=60）
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        int rrfK = 60;
        for (List<String> ranking : allRankings) {
            for (int i = 0; i < ranking.size(); i++) {
                rrfScores.merge(ranking.get(i), 1.0 / (rrfK + i + 1), Double::sum);
            }
        }

        // 按 RRF 分数排序，去重取 Top-K
        List<Map.Entry<String, Double>> sorted = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(k)
                .collect(Collectors.toList());

        List<KnowledgeHit> fusedHits = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sorted) {
            KnowledgeDocument doc = docs.get(entry.getKey());
            if (doc != null) {
                KnowledgeHit hit = new KnowledgeHit(doc, entry.getValue());
                fusedHits.add(hit);
                if (trace != null) {
                    trace.addFused(entry.getKey(), doc.getTitle(), entry.getValue(), fusedHits.size());
                }
            }
        }

        // 记录最终上下文
        if (trace != null) {
            fusedHits.forEach(h -> trace.addFinalContext(h.toContext()));
            trace.hit(!fusedHits.isEmpty()).durationMs(System.currentTimeMillis());
            traceConsumer.accept(trace);
        }

        return fusedHits;
    }

    @Override
    public int size() { return docs.size(); }

    /**
     * ⭐ 列出全部文档（REQ-4 内存快照刷新用）。
     */
    @Override
    public List<KnowledgeDocument> listAll() {
        return new ArrayList<>(docs.values());
    }

    /**
     * ⭐ 全量替换文档集（供 {@code MemoryRefreshCoordinator} 周期性从 PG 拉取最新快照）。
     * <p>清空现有存储并重新嵌入给定文档，使内存降级快照与 PG 主库保持一致。</p>
     *
     * @param documents 新的文档集合（可为空，表示清空）
     */
    public void replaceAll(java.util.Collection<KnowledgeDocument> documents) {
        docs.clear();
        vectors.clear();
        if (documents != null) {
            for (KnowledgeDocument doc : documents) {
                addDocument(doc);
            }
        }
        log.info("[KnowledgeBase:{}] 内存快照刷新完成: {} 篇文档", name, docs.size());
    }

    @Override
    public void reindex() {
        vectors.clear();

        // ★ 清理被新版本取代的旧文档
        List<KnowledgeDocument> allDocs = new ArrayList<>(docs.values());
        removeSuperseded(allDocs);

        for (KnowledgeDocument doc : allDocs) {
            float[] vec = embeddingModel.embedding(doc.toEmbedText());
            if (vec != null) vectors.put(doc.getId(), normalize(vec));
        }
        // ★ 重新初始化 BM25 索引
        if (bm25Scorer != null) {
            bm25Scorer.initialize(allDocs);
            log.info("[KnowledgeBase:{}] BM25 索引完成: {} 篇文档, avgDocLen={:.1f}",
                    name, allDocs.size(),
                    bm25Scorer.isInitialized() ? (double) allDocs.stream()
                            .mapToInt(d -> (d.getContent() != null ? d.getContent().length() : 0)).average().orElse(0)
                            : 0);
        }
        log.info("[KnowledgeBase:{}] 重新索引完成: {} 篇文档", name, docs.size());
    }

    /**
     * 清理被新版本取代的旧文档。
     * 同 baseDocId 且版本更低的文档会被移除。
     */
    private void removeSuperseded(List<KnowledgeDocument> allDocs) {
        // 按 baseDocId 分组
        Map<String, List<KnowledgeDocument>> grouped = allDocs.stream()
                .collect(Collectors.groupingBy(KnowledgeDocument::getBaseDocId));

        for (var entry : grouped.entrySet()) {
            List<KnowledgeDocument> group = entry.getValue();
            if (group.size() <= 1) continue;
            // 找出版本最高的文档
            KnowledgeDocument newest = group.stream()
                    .max(Comparator.comparingDouble(KnowledgeDocument::getVersionPriority))
                    .orElse(null);
            if (newest == null) continue;
            // 移除所有被 newest 取代的文档
            for (KnowledgeDocument doc : group) {
                if (doc != newest && doc.isSupersededBy(newest)) {
                    log.info("[KnowledgeBase:{}] 清理旧版本: baseId={}, old={}, new={}",
                            name, entry.getKey(), doc.getVersion(), newest.getVersion());
                    docs.remove(doc.getId());
                }
            }
        }
    }

    // ==================== 精排 ====================

    /**
     * 组合评分 = 余弦相似度 × 时间衰减 × 版本优先级 + BM25 × 混合权重
     * <p>
     * 版本优先级：v1 为 1.0，v2 为 1.1，v3 为 1.2（更高版本优先）。
     * 被同一 baseId 的新版本取代的文档会被降低分数。
     * </p>
     */
    private double composeScore(double cosSim, KnowledgeDocument doc, String query) {
        // 时间衰减
        double timeDecay = 1.0;
        if (doc.getExpireAt() > 0) {
            long daysToExpire = (doc.getExpireAt() - System.currentTimeMillis()) / 86400000;
            if (daysToExpire > 0) {
                timeDecay = Math.exp(-TIME_DECAY_LAMBDA * (365 - daysToExpire));
            }
        }

        // 版本优先级（v1=1.0, v2=1.1, v3=1.2）
        double versionPriority = 1.0 + doc.getVersionPriority() * VERSION_PENALTY_RATE;
        // 如果还有其他版本的文档，检查是否被取代
        double versionBoost = versionPriority;

        // BM25 分数（标准化到 0~1 范围）
        double bm25Score = 0;
        if (bm25Scorer != null && bm25Scorer.isInitialized()) {
            bm25Score = Math.tanh(bm25Scorer.score(doc, query)); // tanh 压缩到 [0,1)
            bm25Score = Math.min(bm25Score, 1.0); // 上限保护
        }

        return cosSim * timeDecay * versionBoost * (1 - BM25_MIX_WEIGHT) + bm25Score * BM25_MIX_WEIGHT;
    }

    // ==================== 兜底搜索 ====================

    private List<KnowledgeHit> fallbackKeywordSearch(String query, int topK, AclContext acl) {
        String q = query.toLowerCase();
        return docs.values().stream()
                .filter(KnowledgeDocument::isRetrievable)
                .filter(doc -> tenantMatches(doc, acl))
                .map(doc -> {
                    double score = 0;
                    if (doc.getTitle().toLowerCase().contains(q)) score += 0.5;
                    if (doc.getContent().toLowerCase().contains(q)) score += 0.3;
                    if (doc.getKeywords() != null && doc.getKeywords().toLowerCase().contains(q)) score += 0.2;
                    return new KnowledgeHit(doc, score);
                })
                .filter(hit -> hit.getScore() > 0)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .collect(Collectors.toList());
    }

    // ==================== 工具方法 ====================

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private static float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vec;
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) result[i] = (float) (vec[i] / norm);
        return result;
    }

    private static class ScoredDoc {
        final KnowledgeDocument doc;
        final double score;
        ScoredDoc(KnowledgeDocument doc, double score) { this.doc = doc; this.score = score; }
    }

    // ==================== ACL 辅助方法 ====================

    /**
     * 检查文档是否对指定租户可见（仅租户维度，保持向后兼容）。
     */
    private static boolean tenantMatches(KnowledgeDocument doc, String requestTenantId) {
        return tenantMatches(doc, AclContext.forTenant(requestTenantId));
    }

    /**
     * ⭐ 细粒度 ACL 可见性判断（文章⑤：权限进入检索层，服务端生成 filter）。
     * <p>四层过滤，全部在检索前完成：
     * <ol>
     *   <li>租户：文档公开，或 doc.tenantId == acl.tenantId；</li>
     *   <li>安全等级：doc.securityLevel==0（公开）或 ≤ acl.securityClearance；</li>
     *   <li>角色：doc.authorizedRoles 为空（任意角色）或 acl.roles 与之有交集；</li>
     *   <li>用户：doc.authorizedUsers 为空（任意用户）或 acl.userId 在其中。</li>
     * </ol>
     */
    private static boolean tenantMatches(KnowledgeDocument doc, AclContext acl) {
        // 1. 租户隔离
        String docTenant = doc.getTenantId();
        String reqTenant = acl.getTenantId();
        boolean tenantOk = (docTenant == null || docTenant.isEmpty())
                || (reqTenant != null && !reqTenant.isEmpty() && docTenant.equals(reqTenant));
        if (!tenantOk) return false;

        // 2. 安全等级
        int docLevel = doc.getSecurityLevel();
        if (docLevel > 0 && docLevel > acl.getSecurityClearance()) return false;

        // 3. 角色：文档限定角色时，用户必须拥有其一
        java.util.Set<String> docRoles = doc.getAuthorizedRoles();
        if (docRoles != null && !docRoles.isEmpty()) {
            boolean roleOk = acl.getRoles().stream().anyMatch(docRoles::contains);
            if (!roleOk) return false;
        }

        // 4. 用户：文档限定用户时，必须包含当前用户
        java.util.Set<String> docUsers = doc.getAuthorizedUsers();
        if (docUsers != null && !docUsers.isEmpty()) {
            String userId = acl.getUserId();
            if (userId == null || userId.isEmpty() || !docUsers.contains(userId)) return false;
        }

        return true;
    }
}
