/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.properties.RagProductionProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductKnowledgeScopeSelectorTest {

    @Test
    void shouldSelectRegisteredCapabilitiesWithoutInspectingKeywords() {
        InMemoryKnowledgeBase products = mock(InMemoryKnowledgeBase.class);
        InMemoryKnowledgeBase orders = mock(InMemoryKnowledgeBase.class);
        when(products.getName()).thenReturn("product_knowledge");
        when(orders.getName()).thenReturn("order_knowledge");
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService()
                .register(products).register(orders);
        ProductKnowledgeScopeSelector selector = new ProductKnowledgeScopeSelector(
                retrieval, new RagProductionProperties());

        var scope = selector.select("product", List.of("product-search"), "订单物流和商品价格");

        assertEquals(List.of("product_knowledge"), scope.knowledgeBases());
        assertEquals("product-agent-capability", scope.reason());
        assertEquals(List.of(), selector.select("order", List.of("order-search"), "商品价格")
                .knowledgeBases());
    }

    @Test
    void usesConfiguredProductBaseAndNeverFallsBackToUnrelatedBase() {
        InMemoryKnowledgeBase custom = mock(InMemoryKnowledgeBase.class);
        when(custom.getName()).thenReturn("catalog_knowledge");
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService().register(custom);
        RagProductionProperties properties = new RagProductionProperties();
        properties.getRag().getPg().setKnowledgeBaseName("catalog_knowledge");
        ProductKnowledgeScopeSelector selector = new ProductKnowledgeScopeSelector(retrieval, properties);

        assertEquals(List.of("catalog_knowledge"), selector.select("product", List.of(), "test")
                .knowledgeBases());
        properties.getRag().getPg().setKnowledgeBaseName("missing_knowledge");
        assertEquals(List.of(), selector.select("product", List.of(), "test")
                .knowledgeBases());
    }
}
