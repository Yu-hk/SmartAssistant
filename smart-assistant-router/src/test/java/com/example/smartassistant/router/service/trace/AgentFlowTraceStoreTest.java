package com.example.smartassistant.router.service.trace;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

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
                List.of("product", "order"), 900);

        var snapshot = store.get("req-1").orElseThrow();
        assertEquals("completed", snapshot.status());
        assertEquals("deepseek-reasoner", snapshot.modelName());
        assertTrue(snapshot.edges().stream().anyMatch(edge ->
                "product".equals(edge.from()) && "order".equals(edge.to())));
        assertEquals("completed", snapshot.nodes().stream()
                .filter(node -> "order".equals(node.id())).findFirst().orElseThrow().status());
        var merger = snapshot.nodes().stream()
                .filter(node -> "__result_merger__".equals(node.id())).findFirst().orElseThrow();
        assertEquals("", merger.agent());
        assertEquals("已完成执行结果汇总", merger.summary());
        assertEquals("", snapshot.question());
        assertEquals(4, snapshot.nodes().size());
    }

    @Test void writesOnlyMetadataAndSanitizesLegacyReads() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var mapper = new ObjectMapper();
        var store = new AgentFlowTraceStore(mapper, redis);
        var graph = new IntentGraph("private-question", List.of(
                new IntentGraph.IntentNode("n1", "private-profile-description", "product", List.of())));
        store.start("req", graph.getQuestion(), null, graph);
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("routing:execution-graph:req"), json.capture(), eq(24L), eq(TimeUnit.HOURS));
        assertFalse(json.getValue().contains("private-"));
        var legacy = new com.example.smartassistant.router.model.AgentFlowSnapshot("req", "private-question", "model", "light", 12,
                "completed", 1L, 2L, List.of(new com.example.smartassistant.router.model.AgentFlowSnapshot.Node(
                "n1", "private-label", "product", "agent", "completed", "private-reply-profile", List.of(), 1L)), List.of());
        when(values.get(anyString())).thenReturn(mapper.writeValueAsString(legacy));
        assertFalse(mapper.writeValueAsString(store.get("req").orElseThrow()).contains("private-"));
        store.complete("req", List.of(new SubTaskResult("n1","private-label","product","private-result",true)), List.of("product"), 4L);
        verify(values, times(2)).set(anyString(), json.capture(), eq(24L), eq(TimeUnit.HOURS));
        assertFalse(json.getValue().contains("private-"));
    }

    @Test void localFallbackExpiresAndIsBounded() {
        var clock = new AtomicLong();
        var store = new AgentFlowTraceStore(new ObjectMapper(), null, clock::get, 2);
        var graph = new IntentGraph("q", List.of(new IntentGraph.IntentNode("n","task","product",List.of())));
        for (int i=0;i<20;i++) store.start("r"+i,"q",null,graph);
        @SuppressWarnings("unchecked") var cache = (com.github.benmanes.caffeine.cache.Cache<String, ?>)
                org.springframework.test.util.ReflectionTestUtils.getField(store,"localFallback");
        cache.cleanUp();
        assertTrue(cache.estimatedSize()<=2);
        clock.addAndGet(TimeUnit.HOURS.toNanos(25));
        for (int i=0;i<20;i++) assertTrue(store.get("r"+i).isEmpty());
    }

    @Test void redisDeletionCannotResurrectLocalCopyAndFailureNeverContainsRawError() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var store = new AgentFlowTraceStore(new ObjectMapper(),redis);
        var graph = new IntentGraph("q", List.of(new IntentGraph.IntentNode("n","task","product",List.of())));
        store.start("r","q",null,graph);
        when(values.get(anyString())).thenThrow(new IllegalStateException("private-error"));
        store.fail("r","private-error");
        assertTrue(store.get("r").orElseThrow().nodes().stream().noneMatch(n->n.summary().contains("private")));
        doReturn(null).when(values).get(anyString());
        assertTrue(store.get("r").isEmpty());
        doThrow(new IllegalStateException("offline")).when(values).get(anyString());
        assertTrue(store.get("r").isEmpty());
    }
}
