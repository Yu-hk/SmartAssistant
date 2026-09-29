/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.properties.RagProductionProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Product agent scope selector backed by the knowledge bases registered in this service. */
@Component
public class ProductKnowledgeScopeSelector implements KnowledgeScopeSelector {

    private final KnowledgeRetrievalService retrievalService;
    private final RagProductionProperties properties;

    public ProductKnowledgeScopeSelector(KnowledgeRetrievalService retrievalService,
                                         RagProductionProperties properties) {
        this.retrievalService = retrievalService;
        this.properties = properties;
    }

    @Override
    public KnowledgeScope select(String agentName, List<String> skills, String query) {
        if (!"product".equals(agentName)) {
            return new KnowledgeScope(List.of(), "unsupported-agent");
        }
        String productBase = properties.getRag().getPg().getKnowledgeBaseName();
        if (productBase == null || productBase.isBlank()
                || !retrievalService.getKnowledgeBaseNames().contains(productBase)) {
            return new KnowledgeScope(List.of(), "product-base-not-registered");
        }
        // Scope comes from the product agent's configured capability, not user keywords.
        // Never silently broaden a product request to an order or unrelated knowledge base.
        return new KnowledgeScope(List.of(productBase), "product-agent-capability");
    }
}
