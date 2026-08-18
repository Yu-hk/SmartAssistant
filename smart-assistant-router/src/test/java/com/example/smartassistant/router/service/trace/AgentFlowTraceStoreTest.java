package com.example.smartassistant.router.service.trace;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentFlowTraceStoreTest {

    @Test
    void persistsRealDependenciesAndFinalNodeStates() {
        AgentFlowTraceStore store = new AgentFlowTraceStore(new ObjectMapper(), null);
        TaskAnalysisResult analysis = new TaskAnalysisResult();
        analysis.setAnalysisModel("deepseek-reasoner");
        analysis.setAnalysisModelTier("heavy");
        analysis.setAnalysisQuestionChars(220);
        analysis.setAnalysisLatencyMs(480);
        IntentGraph graph = new IntentGraph("先查商品再查订单", List.of(
                new IntentGraph.IntentNode("product", "查商品", "product", List.of()),
                new IntentGraph.IntentNode("order", "查订单", "order", List.of("product"))));

        store.start("req-1", graph.getQuestion(), analysis, graph);
        store.complete("req-1", List.of(
                new SubTaskResult("product", "查商品", "product", "商品结果", true),
                new SubTaskResult("order", "查订单", "order", "订单结果", true)),
                "orchestrator", 900);

        var snapshot = store.get("req-1").orElseThrow();
        assertEquals("completed", snapshot.status());
        assertEquals("deepseek-reasoner", snapshot.modelName());
        assertTrue(snapshot.edges().stream().anyMatch(edge ->
                "product".equals(edge.from()) && "order".equals(edge.to())));
        assertEquals("completed", snapshot.nodes().stream()
                .filter(node -> "order".equals(node.id())).findFirst().orElseThrow().status());
        assertEquals(4, snapshot.nodes().size());
    }
}
