/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.graph.EntityNode;
import com.example.smartassistant.common.rag.graph.EntityRelation;
import com.example.smartassistant.common.rag.graph.KnowledgeGraphService;
import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H06: LightRAG 式实体关系图检索 Handler。
 *
 * <p>LightRAG 的核心思想是：除了检索文本块，还要从「实体-关系图」中检索
 * 与查询相关的实体及其关联。本 Handler 在查询阶段完成这一检索路径：
 * <ol>
 *     <li>从查询中抽取消歧关键词（整句 + 中英分词）；</li>
 *     <li>在知识图谱中 {@link KnowledgeGraphService#searchNodes(String)} 匹配实体；</li>
 *     <li>以匹配实体为起点，扩展 1 跳关系（{@link KnowledgeGraphService#getRelationsForNode(String)}），
 *         组装「实体—关系—实体」三元组；</li>
 *     <li>作为一条独立召回路径写入 {@link RagSearchContext}，由 {@code RrfFusionHandler} 自动并入最终答案。</li>
 * </ol>
 *
 * <p>与 {@code GraphSearchHandler}（H05，仅基于写死的商品编码关系图谱做推荐）不同，
 * 本 Handler 消费的是通用知识图谱（由 LLM 从文档抽取实体/关系构建），
 * 可回答「A 和 B 什么关系」「为什么 X 影响 Y」这类多跳/关系型问题。</p>
 *
 * <p>优雅降级：图谱 Bean 未注入、图为空、或检索异常时静默跳过，不影响其它召回路径。</p>
 */
@Component
@ConditionalOnProperty(name = "product.rag.lightrag.enabled", havingValue = "true", matchIfMissing = true)
public class LightRagSearchHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(LightRagSearchHandler.class);

    /** 召回路径名（被 RrfFusionHandler 自动纳入融合） */
    static final String PATH_NAME = "实体关系图检索";

    /** 起点实体数量上限 */
    private static final int MAX_SEED_ENTITIES = 6;

    /** 三元组数量上限 */
    private static final int MAX_TRIPLES = 15;

    /** 中文 2 字以上 + 连续英文/数字 token */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9]+|[一-鿿]{2,}");

    private final KnowledgeGraphService graphService;

    public LightRagSearchHandler(@Autowired(required = false) KnowledgeGraphService graphService) {
        this.graphService = graphService;
        if (graphService != null) {
            log.info("[LightRAG] 已接入知识图谱 Bean（nodeCount={}）", graphService.nodeCount());
        } else {
            log.info("[LightRAG] 未注入知识图谱 Bean，Handler 将以空操作运行");
        }
    }

    @Override
    public int getOrder() {
        // 在 GraphSearchHandler(50) 之后、RrfFusionHandler(100) 之前
        return 60;
    }

    @Override
    public void handle(RagSearchContext context) {
        if (graphService == null || graphService.nodeCount() == 0) {
            return; // 图未就绪 → 静默降级，不污染检索
        }
        try {
            List<String> items = retrieve(context.getOriginalQuery());
            if (!items.isEmpty()) {
                context.addPathResult(PATH_NAME, items);
                int tripleCount = Math.max(0, items.size() - 1); // 减去首行标题
                context.setAttribute("lightRagTripleCount", tripleCount);
                log.info("[LightRAG] 图检索命中: query={}, pathItems={}", context.getOriginalQuery(), items.size());
            }
        } catch (Exception e) {
            log.warn("[LightRAG] 图检索异常: {}", e.getMessage());
        }
    }

    /**
     * 执行实体关系图检索，返回格式化的召回条目（含首行标题）。
     *
     * <p>包级可见，便于单元测试直接调用。</p>
     */
    List<String> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // 1. 抽取消歧关键词（整句 + 分词），匹配图谱实体
        Set<String> tokens = extractQueryTokens(query);
        LinkedHashMap<String, EntityNode> matched = new LinkedHashMap<>();
        for (String token : tokens) {
            for (EntityNode node : graphService.searchNodes(token)) {
                matched.putIfAbsent(node.getId(), node);
            }
        }
        if (matched.isEmpty()) {
            return List.of();
        }

        // 2. 以匹配实体为起点，扩展 1 跳关系，组装三元组
        List<String> triples = new ArrayList<>();
        int seedIndex = 0;
        for (EntityNode seed : matched.values()) {
            if (seedIndex++ >= MAX_SEED_ENTITIES) break;
            if (triples.size() >= MAX_TRIPLES) break;

            List<EntityRelation> relations = new ArrayList<>(graphService.getRelationsForNode(seed.getId()));
            // 置信度高的关系优先
            relations.sort(Comparator.comparing(
                    r -> r.getConfidence() == null ? 0d : r.getConfidence(), Comparator.reverseOrder()));

            for (EntityRelation rel : relations) {
                if (triples.size() >= MAX_TRIPLES) break;
                String otherId = rel.getSourceId().equals(seed.getId()) ? rel.getTargetId() : rel.getSourceId();
                if (otherId.equals(seed.getId())) continue; // 跳过自环
                EntityNode other = graphService.getNode(otherId);
                if (other == null) continue; // 对端实体未被抽取，跳过
                triples.add(formatTriple(seed, rel, other));
            }
        }

        if (triples.isEmpty()) {
            return List.of();
        }

        List<String> items = new ArrayList<>(triples.size() + 1);
        items.add("【实体关系图检索】匹配实体 " + matched.size() + " 个，关联三元组 " + triples.size() + " 条：");
        items.addAll(triples);
        return items;
    }

    private static String formatTriple(EntityNode a, EntityRelation rel, EntityNode b) {
        String desc = (rel.getDescription() != null && !rel.getDescription().isBlank())
                ? "（" + rel.getDescription() + "）" : "";
        return "实体【" + a.getName() + "(" + a.getType() + ")】 "
                + rel.getRelationType() + " 实体【"
                + b.getName() + "(" + b.getType() + ")】" + desc;
    }

    /**
     * 从查询中抽取消歧 token：保留整句，并按中英文分词；
     * 中文长串额外做二元滑动切分，提升实体召回率
     * （如「手机保修」→ 手机 / 保修，可分别命中「手机数码」「保修」实体）。
     */
    private static Set<String> extractQueryTokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        tokens.add(query.trim());
        Matcher matcher = TOKEN_PATTERN.matcher(query);
        while (matcher.find()) {
            String tok = matcher.group();
            tokens.add(tok);
            // 中文/长串额外二元切分（长度 > 2 才需要，避免单字噪声）
            if (tok.length() > 2) {
                for (int i = 0; i + 2 <= tok.length(); i++) {
                    tokens.add(tok.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }
}
