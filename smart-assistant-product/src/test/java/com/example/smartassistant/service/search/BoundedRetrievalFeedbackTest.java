package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.*;
import com.example.smartassistant.common.rag.pipeline.*;
import com.example.smartassistant.config.NativeRagProperties;
import com.example.smartassistant.service.search.handler.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BoundedRetrievalFeedbackTest {
    private static final String QUESTION = "如何计算可售库存，锁定库存规则是什么？";
    private static final String OLD = "📚 知识库「allowed」查询结果：\n\n1. 【可售库存概述】（相关度: 90%）[CID:OLD]\n可售库存\n";
    private static final String COMPLETE = OLD + "2. 【可售库存计算规则】（相关度: 80%）[CID:NEW]\n可售库存计算规则：锁定库存不能作为可售库存。\n";
    @AfterEach void clearIdentity() { MDC.clear(); }
    NativeRagProperties properties() {
        var p = new NativeRagProperties(); p.setAutomaticRetryEnabled(true); return p;
    }
    RagSearchContext baseline(String body) {
        var c = new RagSearchContext(QUESTION); c.setQualityThreshold(.3); c.setQualityScore(1);
        c.setAttribute("rag.knowledgeBases", List.of("allowed"));
        if (!body.isEmpty()) { c.addPathResult("知识库", List.of(body)); c.setFusedResults(List.of(new RagSearchContext.RankedItem(body, 1))); }
        return c;
    }
    RagSearchPipeline pipeline(KnowledgeRetrievalService kb) {
        var rrf = new RrfFusionHandler(); ReflectionTestUtils.setField(rrf, "qualityThreshold", .3);
        return new RagSearchPipeline(List.of(new KnowledgeSearchHandler(kb), rrf, new DedupHandler()));
    }
    @Test void retryImprovesEvidenceAndKeepsScopeAclQueryAndNoPlanner() {
        var kb = mock(KnowledgeRetrievalService.class);
        MDC.put("tenantId", "tenant-a"); MDC.put("userId", "user-a"); MDC.put("aclRoles", "reader"); MDC.put("securityClearance", "2");
        when(kb.search(eq("allowed"), eq(QUESTION), eq(8), any(AclContext.class))).thenAnswer(call -> {
            AclContext acl = call.getArgument(3);
            assertEquals("tenant-a", acl.getTenantId()); assertEquals("user-a", acl.getUserId());
            assertEquals(Set.of("reader"), acl.getRoles()); assertEquals(2, acl.getSecurityClearance());
            return COMPLETE;
        });
        try (var loop = new BoundedRetrievalFeedback(pipeline(kb), properties())) {
            var decision = loop.apply(QUESTION, baseline(OLD), 8);
            assertEquals("ACCEPTED", decision.diagnostics().get("status"));
            assertSame(decision.candidate(), decision.selected());
            assertEquals(List.of(QUESTION), decision.candidate().getQueryVariants());
            assertEquals(.7, decision.candidate().getAttribute(AdaptiveWeightHandler.ATTR_SPARSE_WEIGHT));
            assertEquals("tenant-a", MDC.get("tenantId"));
        }
        verify(kb, times(1)).search(eq("allowed"), eq(QUESTION), eq(8), any(AclContext.class));
    }
    @Test void missingOldCitationOrNoGainKeepsBaseline() {
        for (String answer : List.of(OLD, "[CID:OTHER] 可售库存计算规则：锁定库存不能作为可售库存。")) {
            var kb = mock(KnowledgeRetrievalService.class);
            when(kb.search(anyString(), anyString(), eq(8), any(AclContext.class))).thenReturn(answer);
            var original = baseline(OLD);
            try (var loop = new BoundedRetrievalFeedback(pipeline(kb), properties())) {
                var decision = loop.apply(QUESTION, original, 8);
                assertSame(original, decision.selected()); assertEquals("NO_SAFE_IMPROVEMENT", decision.diagnostics().get("status"));
            }
        }
    }
    @Test void missingEvidenceCanBeRecoveredButUncitedOrOversizedCannot() {
        for (String answer : List.of(COMPLETE, COMPLETE.replaceAll("\\[CID:[^]]+]", ""), COMPLETE + "额".repeat(12000))) {
            var kb = mock(KnowledgeRetrievalService.class);
            when(kb.search(anyString(), anyString(), eq(8), any(AclContext.class))).thenReturn(answer);
            try (var loop = new BoundedRetrievalFeedback(pipeline(kb), properties())) {
                var decision = loop.apply(QUESTION, baseline(""), 8);
                assertEquals(answer.equals(COMPLETE), decision.diagnostics().get("accepted"));
            }
        }
    }
    @Test void mutationAndNonKnowledgeNeverSearch() {
        var kb = mock(KnowledgeRetrievalService.class);
        try (var loop = new BoundedRetrievalFeedback(pipeline(kb), properties())) {
            for (String q : List.of("帮我下单，说明一下库存", "如何充值", "怎么取消订单", "耳机很好", "如何修改地址", "x".repeat(501)))
                assertEquals("NOT_READ_ONLY_KNOWLEDGE", loop.apply(q, baseline(OLD), 8).diagnostics().get("status"));
        }
        verifyNoInteractions(kb);
    }
    @Test void degradedAndNonFiniteBaselineDoNotRetry() {
        var kb = mock(KnowledgeRetrievalService.class);
        try (var loop = new BoundedRetrievalFeedback(pipeline(kb), properties())) {
            var broken = baseline(OLD); broken.addError("first", "OFFLINE", "test");
            assertEquals("BASELINE_DEGRADED_OR_BUDGET", loop.apply(QUESTION, broken, 8).diagnostics().get("status"));
            broken = baseline(OLD); broken.setQualityScore(Double.NaN);
            assertEquals("BASELINE_DEGRADED_OR_BUDGET", loop.apply(QUESTION, broken, 8).diagnostics().get("status"));
        }
        verifyNoInteractions(kb);
    }
    @Test void firstEvidenceCompleteAndFlagOffDoNotRetry() {
        var kb = mock(KnowledgeRetrievalService.class); var p = properties();
        try (var loop = new BoundedRetrievalFeedback(pipeline(kb), p)) {
            var d = loop.apply(QUESTION, baseline(COMPLETE), 8);
            assertEquals("SUFFICIENT_PROXY", d.diagnostics().get("status"), d.diagnostics().toString());
            p.setAutomaticRetryEnabled(false);
            assertEquals("DISABLED", loop.apply(QUESTION, baseline(OLD), 8).diagnostics().get("status"));
        }
        verifyNoInteractions(kb);
    }
    @Test void retryErrorIsNotEmptySuccess() {
        var kb = mock(KnowledgeRetrievalService.class);
        when(kb.search(anyString(), anyString(), eq(8), any(AclContext.class))).thenThrow(new IllegalStateException("offline"));
        var original = baseline(OLD);
        try (var loop = new BoundedRetrievalFeedback(pipeline(kb), properties())) {
            var d = loop.apply(QUESTION, original, 8);
            assertSame(original, d.selected()); assertEquals("NO_SAFE_IMPROVEMENT", d.diagnostics().get("status"));
            assertTrue(d.candidate().isDegraded());
        }
    }
    @Test void genericMissingWordsDoNotImportOtherProductManuals() {
        var kb = mock(KnowledgeRetrievalService.class);
        String unrelated = OLD + "2. 【其他耳机计算规则】（相关度: 80%）[CID:UNRELATED]\n可售库存计算规则：锁定库存不能作为可售库存。\n";
        when(kb.search(anyString(), anyString(), eq(8), any(AclContext.class))).thenReturn(unrelated);
        var original = baseline(OLD);
        try (var loop = new BoundedRetrievalFeedback(pipeline(kb), properties())) {
            var d = loop.apply(QUESTION, original, 8);
            assertSame(original, d.selected()); assertEquals(0, d.diagnostics().get("addedChunks"));
        }
    }
    @Test void filteredExpansionPreservesExactOldTextAndDropsUnrelatedChunks() {
        var kb = mock(KnowledgeRetrievalService.class);
        when(kb.search(anyString(), anyString(), eq(8), any(AclContext.class))).thenReturn(COMPLETE +
                "3. 【其他耳机库存规则】（相关度: 70%）[CID:UNRELATED]\n锁定库存计算规则。\n");
        try (var loop = new BoundedRetrievalFeedback(pipeline(kb), properties())) {
            var d = loop.apply(QUESTION, baseline(OLD), 8);
            assertEquals("ACCEPTED", d.diagnostics().get("status"));
            assertEquals(1, d.diagnostics().get("addedChunks"));
            assertTrue(d.selected().getPathResults().get("知识库").getItems().contains(OLD));
            assertFalse(d.selected().getPathResults().get("知识库").getItems().toString().contains("UNRELATED"));
        }
    }
    @Test void timeoutReturnsBaselineAndCancelsWithoutQueue() throws Exception {
        var kb = mock(KnowledgeRetrievalService.class); var p = properties(); p.setRetryTimeoutMs(100);
        var cancelled = new CountDownLatch(1);
        when(kb.search(anyString(), anyString(), eq(8), any(AclContext.class))).thenAnswer(call -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { cancelled.countDown(); Thread.currentThread().interrupt(); }
            return COMPLETE;
        });
        var original = baseline(OLD);
        try (var loop = new BoundedRetrievalFeedback(pipeline(kb), p)) {
            long start = System.nanoTime(); var d = loop.apply(QUESTION, original, 8);
            assertSame(original, d.selected()); assertEquals("TIMEOUT", d.diagnostics().get("status"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 1000);
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        }
    }
    @Test void busyPoolSkipsInsteadOfAccumulatingRequests() throws Exception {
        var kb = mock(KnowledgeRetrievalService.class); var started = new CountDownLatch(2); var finish = new CountDownLatch(1);
        when(kb.search(anyString(), anyString(), eq(8), any(AclContext.class))).thenAnswer(call -> { started.countDown(); finish.await(); return COMPLETE; });
        var callers = Executors.newFixedThreadPool(2);
        try (var loop = new BoundedRetrievalFeedback(pipeline(kb), properties())) {
            var a = callers.submit(() -> loop.apply(QUESTION, baseline(OLD), 8));
            var b = callers.submit(() -> loop.apply(QUESTION, baseline(OLD), 8));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals("CAPACITY_LIMIT", loop.apply(QUESTION, baseline(OLD), 8).diagnostics().get("status"));
            finish.countDown(); a.get(); b.get();
        } finally { finish.countDown(); callers.shutdownNow(); }
    }
    @Test void serviceUsesRealRetryAndReportsItWithoutLegacyPlanner() {
        var kb = mock(KnowledgeRetrievalService.class);
        when(kb.search(anyString(), eq(QUESTION), anyInt(), any(AclContext.class)))
                .thenAnswer(call -> ((Integer)call.getArgument(2)) == 5 ? OLD : COMPLETE);
        var planner = mock(SupplementalQueryPlanner.class);
        var service = new ProductRagService(pipeline(kb), (a,b,c) -> new KnowledgeScopeSelector.KnowledgeScope(List.of("allowed"), "test"), planner, properties());
        try {
            var result = service.retrieveWithQualityResult(QUESTION, "test-id");
            assertTrue(result.isHighQuality()); assertEquals(true, result.getDiagnostics().get("executeRetry"));
            assertEquals(2, result.getDiagnostics().get("selectedAttempt")); assertTrue(result.getContent().contains("检索轮次：2"));
            assertEquals("ACCEPTED", ((Map<?,?>)result.getDiagnostics().get("feedback")).get("status"));
            verifyNoInteractions(planner);
        } finally { service.closeFeedback(); }
    }
}
