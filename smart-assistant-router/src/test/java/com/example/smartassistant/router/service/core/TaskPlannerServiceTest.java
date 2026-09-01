package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.gateway.llm.AgentLLMGateway;
import com.example.smartassistant.common.gateway.llm.LLMCallResult;
import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.example.smartassistant.router.service.taskanalysis.IntentDef;
import com.example.smartassistant.router.service.taskanalysis.IntentRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskPlannerServiceTest {

    @Test
    void preservesOriginalScenarioForModelAssignedProductNode() {
        var product = new IntentGraph.IntentNode(
                "t1", "查询当前热门商品列表", "product_agent", List.of(), "返回目录商品");
        var order = new IntentGraph.IntentNode(
                "t2", "说明订单操作", "order_agent", List.of(), "返回操作说明");
        String original = "查询热门商品，但目录证据不足时不要宣称适合视频会议";

        List<IntentGraph.IntentNode> scoped = TaskPlannerService.preserveProductContext(
                List.of(product, order), original);

        assertTrue(scoped.getFirst().getDescription().contains(original));
        assertTrue(scoped.getFirst().getDescription().contains("用户原始商品需求与约束"));
        assertEquals("说明订单操作", scoped.get(1).getDescription());
    }

    @Test
    void modelFailureFallsBackToSemanticAgentFromRegisteredCapabilities() {
        AgentDiscoveryService discovery = mock(AgentDiscoveryService.class);
        when(discovery.getCachedAgents()).thenReturn(List.of(
                agent("product_agent", "product_agent", "product_query", 100),
                agent("order_agent", "order_agent", "order_query,logistics", 10)));
        AgentLLMGateway gateway = mock(AgentLLMGateway.class);
        when(gateway.call(any(), anyString(), any())).thenReturn(
                LLMCallResult.failure("invalid key", 1));
        DeepSeekPlanningClient planningClient = mock(DeepSeekPlanningClient.class);
        when(planningClient.modelName()).thenReturn("planner");
        IntentRetriever retriever = mock(IntentRetriever.class);
        when(retriever.retrieve("查看我的订单列表", 1)).thenReturn(List.of(
                new IntentDef("ORDER", "order", "订单查询", List.of(),
                        "查看我的订单", "order_query")));
        TaskPlannerService planner = new TaskPlannerService(
                discovery, gateway, planningClient, retriever);

        IntentGraph graph = planner.planToGraph("查看我的订单列表");

        assertEquals(1, graph.getNodeCount());
        assertEquals("order_agent", graph.getAllNodes().iterator().next().getTargetAgent());
    }

    private static DiscoveredAgent agent(
            String name, String type, String capabilities, int priority) {
        AgentMetadata metadata = new AgentMetadata();
        metadata.setAgentType(type);
        metadata.setCapabilities(capabilities);
        metadata.setPriority(priority);
        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setAgentName(name);
        agent.setServiceName("smart-assistant-" + name);
        agent.setHealthy(true);
        agent.setMetadata(metadata);
        return agent;
    }
}
