package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.service.cache.BgeOnnxEmbeddingService;
import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRetrieverTest {

    @Test
    void nullEmbeddingFallsBackToKeywordRetrieval() {
        BgeOnnxEmbeddingService embeddingService = mock(BgeOnnxEmbeddingService.class);
        when(embeddingService.embed(anyString())).thenReturn(null);
        AgentDiscoveryService discoveryService = mock(AgentDiscoveryService.class);
        when(discoveryService.getCachedAgents()).thenReturn(List.of(orderAgent()));
        IntentRetriever retriever = new IntentRetriever(embeddingService, discoveryService);

        retriever.init();
        List<IntentDef> intents = retriever.retrieve("商品退货退款需要满足哪些条件？", 3);

        assertFalse(intents.isEmpty());
        assertEquals("ORDER", intents.getFirst().id());
    }

    private static DiscoveredAgent orderAgent() {
        AgentMetadata metadata = new AgentMetadata();
        metadata.setKeywords("订单,退货,退款,物流");
        metadata.setCapabilities("query_order,refund_order");
        metadata.setRoutingExamples("我的订单到哪了||退货需要什么条件");
        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setAgentName("order");
        agent.setServiceName("smart-assistant-order");
        agent.setHealthy(true);
        agent.setMetadata(metadata);
        return agent;
    }
}
