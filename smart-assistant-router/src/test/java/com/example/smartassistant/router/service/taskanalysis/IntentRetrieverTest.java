package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.service.cache.BgeOnnxEmbeddingService;
import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRetrieverTest {

    @Test
    void nullEmbeddingDoesNotRestoreRemovedKeywordRouting() {
        BgeOnnxEmbeddingService embeddingService = mock(BgeOnnxEmbeddingService.class);
        when(embeddingService.embed(anyString())).thenReturn(null);
        AgentDiscoveryService discoveryService = mock(AgentDiscoveryService.class);
        when(discoveryService.getCachedAgents()).thenReturn(List.of(orderAgent()));
        IntentRetriever retriever = new IntentRetriever(embeddingService, discoveryService);

        retriever.init();
        List<IntentDef> intents = retriever.retrieve("商品退货退款需要满足哪些条件？", 3);

        assertTrue(intents.isEmpty());
    }

    @Test
    void routesByRegisteredCapabilitiesAndExcludesInfrastructureServices() {
        BgeOnnxEmbeddingService embeddingService = mock(BgeOnnxEmbeddingService.class);
        when(embeddingService.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0, String.class);
            if (text.contains("ORDER") || text.contains("订单") || text.contains("物流")) {
                return new float[]{1.0f, 0.0f};
            }
            return new float[]{0.0f, 1.0f};
        });
        AgentDiscoveryService discoveryService = mock(AgentDiscoveryService.class);
        when(discoveryService.getCachedAgents()).thenReturn(List.of(
                orderAgent(), productAgent(), infrastructureAgent()));
        IntentRetriever retriever = new IntentRetriever(embeddingService, discoveryService);

        retriever.init();
        List<IntentDef> intents = retriever.retrieve("查看我的订单列表和物流状态", 3);

        assertTrue(!intents.isEmpty() && "order".equals(intents.getFirst().name()));
        assertTrue(intents.stream().noneMatch(intent -> "embedding-service".equals(intent.name())));
    }

    private static DiscoveredAgent orderAgent() {
        AgentMetadata metadata = new AgentMetadata();
        metadata.setKeywords("订单,退货,退款,物流");
        metadata.setAgentType("order_agent");
        metadata.setCapabilities("query_order,refund_order");
        metadata.setRoutingExamples("我的订单到哪了||退货需要什么条件");
        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setAgentName("order");
        agent.setServiceName("smart-assistant-order");
        agent.setHealthy(true);
        agent.setMetadata(metadata);
        return agent;
    }

    private static DiscoveredAgent productAgent() {
        AgentMetadata metadata = new AgentMetadata();
        metadata.setAgentType("product_agent");
        metadata.setCapabilities("product_query,product_recommendation,stock_check");
        metadata.setRoutingExamples("推荐热门商品||查询商品库存");
        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setAgentName("product");
        agent.setServiceName("smart-assistant-product");
        agent.setHealthy(true);
        agent.setMetadata(metadata);
        return agent;
    }

    private static DiscoveredAgent infrastructureAgent() {
        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setAgentName("embedding-service");
        agent.setServiceName("smart-assistant-embedding");
        agent.setHealthy(true);
        agent.setMetadata(new AgentMetadata());
        return agent;
    }
}
