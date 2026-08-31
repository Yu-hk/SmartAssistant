/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ProductKnowledgeScopeSelectorTest {

    @Test
    void shouldSelectRegisteredCapabilitiesWithoutInspectingKeywords() {
        InMemoryKnowledgeBase products = mock(InMemoryKnowledgeBase.class);
        org.mockito.Mockito.when(products.getName()).thenReturn("product_knowledge");
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService().register(products);
        ProductKnowledgeScopeSelector selector = new ProductKnowledgeScopeSelector(retrieval);

        var scope = selector.select("product", List.of("product-search"), "任意文本");

        assertEquals(List.of("product_knowledge"), scope.knowledgeBases());
        assertEquals("registered-agent-capabilities", scope.reason());
    }
}
