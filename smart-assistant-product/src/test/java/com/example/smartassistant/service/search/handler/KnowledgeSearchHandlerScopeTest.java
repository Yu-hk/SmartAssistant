package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.rag.AclContext;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.rag.properties.RagProductionProperties;
import com.example.smartassistant.service.search.ProductKnowledgeScopeSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSearchHandlerScopeTest {

    @Test
    void queriesOnlyTheProductBaseSelectedBeforeRetrieval() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.getKnowledgeBaseNames()).thenReturn(Set.of("product_knowledge", "order_knowledge"));
        when(retrieval.search(eq("product_knowledge"), eq("AirPods 价格"), eq(5), any(AclContext.class)))
                .thenReturn("已核验的商品知识");
        var selector = new ProductKnowledgeScopeSelector(retrieval, new RagProductionProperties());
        RagSearchContext context = new RagSearchContext("AirPods 价格");
        context.setAttribute("rag.knowledgeBases",
                selector.select("product", List.of("product-search"), "AirPods 价格").knowledgeBases());

        new KnowledgeSearchHandler(retrieval).handle(context);

        assertThat(context.getPathResults().get("知识库").getItems()).containsExactly("已核验的商品知识");
        verify(retrieval).search(eq("product_knowledge"), eq("AirPods 价格"), eq(5), any(AclContext.class));
        verify(retrieval, never()).search(eq("order_knowledge"), any(), eq(5), any(AclContext.class));
    }
}
