/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Product agent scope selector backed by the knowledge bases registered in this service. */
@Component
public class ProductKnowledgeScopeSelector implements KnowledgeScopeSelector {

    private final KnowledgeRetrievalService retrievalService;

    public ProductKnowledgeScopeSelector(KnowledgeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public KnowledgeScope select(String agentName, List<String> skills, String query) {
        List<String> registered = new ArrayList<>(retrievalService.getKnowledgeBaseNames());
        registered.sort(String::compareTo);
        return new KnowledgeScope(registered, "registered-agent-capabilities");
    }
}
