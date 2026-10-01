/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.AclContext;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.KnowledgeSeedData;
import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * H04: 经验知识库检索 Handler。
 *
 * <p>通过 KnowledgeQueryTool 查询历史经验知识库。
 */
@Component
public class KnowledgeSearchHandler implements RagSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchHandler.class);

    private final KnowledgeRetrievalService retrievalService;

    public KnowledgeSearchHandler(KnowledgeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public void handle(RagSearchContext context) {
        List<String> results = new ArrayList<>();
        int topK = context.getAttribute("rag.retryKnowledgeTopK") instanceof Number value
                ? Math.max(5, Math.min(8, value.intValue())) : 5;

        for (String knowledgeBase : selectedKnowledgeBases(context)) {
            try {
                String knowledge = retrievalService.search(
                        knowledgeBase, context.getOriginalQuery(), topK, AclContext.fromMdc());
                if (knowledge != null && !knowledge.isBlank()
                        && !knowledge.contains("未找到")
                        && !knowledge.contains("INSUFFICIENT_EVIDENCE")
                        && !knowledge.contains("不存在")
                        && !knowledge.contains("PRODUCT_NOT_FOUND")) {
                    results.add(knowledge);
                }
            } catch (Exception e) {
                context.addError("KnowledgeSearchHandler", "KNOWLEDGE_SEARCH_UNAVAILABLE", e.getClass().getSimpleName());
                log.warn("[RagHandler] KnowledgeSearch 失败 (kb={}): {}", knowledgeBase, e.getMessage());
            }
        }

        context.addPathResult("知识库", results);
        log.info("[RagHandler] KnowledgeSearch: {} results", results.size());
    }

    @Override
    public int getOrder() {
        return 40;
    }

    @SuppressWarnings("unchecked")
    private List<String> selectedKnowledgeBases(RagSearchContext context) {
        Object selected = context.getAttribute("rag.knowledgeBases");
        if (selected instanceof List<?> values) {
            List<String> names = values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(name -> !name.isBlank())
                    .toList();
            return names;
        }
        return List.of(KnowledgeSeedData.PRODUCT_KB);
    }
}
