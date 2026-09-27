package com.example.smartassistant.consumer.service.core;

import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.example.smartassistant.consumer.service.sentiment.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationPreprocessingServiceTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SentimentAnalysisService analyzer = mock(SentimentAnalysisService.class);
    private final SentimentSnapshotStore store = mock(SentimentSnapshotStore.class);
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final SentimentAnalysisService.SentimentResult neutral =
            new SentimentAnalysisService.SentimentResult(2, "中性", "正常回复", false, false, 95);

    @AfterEach void close() { executor.shutdownNow(); }

    private ConversationPreprocessingService service(long timeout) {
        when(store.requestKey(any(), any(), any(), any())).thenReturn("key");
        when(store.claim(eq("key"), any())).thenReturn(true);
        when(store.commit(eq("key"), any(), eq(42L), eq("session"), any()))
                .thenAnswer(call -> call.getArgument(4));
        return new ConversationPreprocessingService(analyzer, store, profiles, executor, timeout);
    }

    @Test void sentimentAndProfileStartConcurrentlyWithoutJoiningProfile() throws Exception {
        var service = service(2000);
        CountDownLatch sentimentStarted = new CountDownLatch(1);
        CountDownLatch profileStarted = new CountDownLatch(1);
        CompletableFuture<String> unfinishedProfile = new CompletableFuture<>();
        when(analyzer.analyze("查询订单")).thenAnswer(call -> {
            sentimentStarted.countDown();
            assertTrue(profileStarted.await(1, TimeUnit.SECONDS));
            return neutral;
        });
        when(profiles.prefetchForRequest(42L, "查询订单", "request")).thenAnswer(call -> {
            assertTrue(sentimentStarted.await(1, TimeUnit.SECONDS));
            profileStarted.countDown();
            return unfinishedProfile;
        });

        TurnInsight result = service.prepare(42L, "session", "request", "查询订单");

        assertEquals("ANALYZED", result.status());
        assertFalse(unfinishedProfile.isDone(), "Preprocessing must not join asynchronous profile work");
        verify(store).commit(eq("key"), any(), eq(42L), eq("session"), eq(result));
    }

    @Test void timeoutIsUnknownAndCancelsInference() throws Exception {
        var service = service(150);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(analyzer.analyze(any())).thenAnswer(call -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException error) { interrupted.countDown(); throw error; }
            return neutral;
        });
        TurnInsight result = service.prepare(42L, "session", "request", "你好");
        assertEquals("TIMEOUT", result.reason());
        assertNull(result.level());
        assertTrue(result.bypassAnswerCache());
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        verify(profiles).prefetchForRequest(42L, "你好", "request");
        verify(store).commit(eq("key"), any(), eq(42L), eq("session"), eq(result));
    }

    @Test void analyzerFailureIsNotReportedAsNeutral() {
        var service = service(1000);
        when(analyzer.analyze(any())).thenThrow(new IllegalStateException("unavailable"));
        var result = service.prepare(42L, "session", "request", "你好");
        assertEquals("ANALYSIS_FAILED", result.reason());
        assertNull(result.level());
        assertEquals("业务回复", result.adaptReply("业务回复"));
    }

    @Test void prequeueAdvisorResultReachesDispatchInsight() {
        var service = service(1000);
        JevPrequeueAdvisor advisor = mock(JevPrequeueAdvisor.class);
        ReflectionTestUtils.setField(service, "jevAdvisor", advisor);
        when(analyzer.analyze("订单被重复扣款了")).thenReturn(neutral);
        when(advisor.augment(eq("订单被重复扣款了"), eq("request"), any()))
                .thenAnswer(call -> {
                    TurnInsight baseline = call.getArgument(2);
                    return new TurnInsight(baseline.status(), baseline.level(), baseline.label(),
                            baseline.confidence(), false, false, baseline.responseStrategy(),
                            "ELEVATED", "DETERMINISTIC_RISK", 0, false);
                });

        TurnInsight result = service.prepare(42L, "session", "request", "订单被重复扣款了");

        assertEquals("ELEVATED", result.suggestedPriority());
        assertEquals("DETERMINISTIC_RISK", result.reason());
    }

    @Test void baselineFailureStillRunsPrequeueRiskAdvisor() {
        var service = service(1000);
        JevPrequeueAdvisor advisor = mock(JevPrequeueAdvisor.class);
        ReflectionTestUtils.setField(service, "jevAdvisor", advisor);
        when(analyzer.analyze("订单被重复扣款了")).thenThrow(new IllegalStateException("model unavailable"));
        when(advisor.augment(eq("订单被重复扣款了"), eq("request"), any()))
                .thenAnswer(call -> {
                    TurnInsight baseline = call.getArgument(2);
                    assertEquals("UNKNOWN", baseline.status());
                    return new TurnInsight(baseline.status(), null, baseline.label(), 0,
                            false, false, baseline.responseStrategy(), "ELEVATED", "DETERMINISTIC_RISK", 0, false);
                });

        TurnInsight result = service.prepare(42L, "session", "request", "订单被重复扣款了");

        assertEquals("ELEVATED", result.suggestedPriority());
        assertEquals("DETERMINISTIC_RISK", result.reason());
    }

    @Test void saturatedExecutorStillStartsProfile() {
        var service = service(1000);
        executor.shutdownNow();
        var result = service.prepare(42L, "session", "request", "你好");
        assertEquals("OVERLOADED", result.reason());
        verify(profiles).prefetchForRequest(42L, "你好", "request");
        verifyNoInteractions(analyzer);
    }

    @Test void redisFailureKeepsCurrentObservationWithoutInventingHistory() {
        var service = service(1000);
        when(store.read("key")).thenThrow(new IllegalStateException("redis unavailable"));
        when(analyzer.analyze(any())).thenReturn(neutral);
        var result = service.prepare(42L, "session", "request", "你好");
        assertEquals("ANALYZED", result.status());
        assertFalse(result.stateRecorded());
        assertFalse(result.escalated());
        verify(store, never()).commit(any(), any(), any(), any(), any());
    }

    @Test void retryReusesSentimentButRenewsShortLivedProfileBarrier() {
        var service = service(1000);
        TurnInsight previous = TurnInsight.analyzed(neutral, 1);
        when(store.read("key")).thenReturn(previous);
        assertSame(previous, service.prepare(42L, "session", "request", "你好"));
        verifyNoInteractions(analyzer);
        verify(store, never()).claim(any(), any());
        verify(store, never()).commit(any(), any(), any(), any(), any());
        verify(profiles).prefetchForRequest(42L, "你好", "request");
    }

    @Test void concurrentRetryUsesOwnerResultWithoutDoubleAnalysis() {
        var service = service(1000);
        TurnInsight previous = TurnInsight.analyzed(neutral, 1);
        when(store.claim(eq("key"), any())).thenReturn(false);
        when(store.read("key")).thenReturn(null, previous);
        assertSame(previous, service.prepare(42L, "session", "request", "你好"));
        verifyNoInteractions(analyzer);
        verify(store, never()).commit(any(), any(), any(), any(), any());
    }

    @Test void optionalProfileFailureDoesNotAbortEmotionAnalysis() {
        var service = service(1000);
        when(analyzer.analyze(any())).thenReturn(neutral);
        when(profiles.prefetchForRequest(any(), any(), any())).thenThrow(new IllegalStateException("barrier"));
        assertEquals("ANALYZED", service.prepare(42L, "session", "request", "你好").status());
    }

    @Test void quotedEvidenceIsExcludedFromEmotionButOriginalQuestionReachesProfile() {
        var service = service(1000);
        String question = "请总结【资料】我要投诉你们，垃圾服务【问题】处理规则";
        when(analyzer.analyze("请总结[用户资料]处理规则")).thenReturn(neutral);
        assertEquals(2, service.prepare(42L, "session", "request", question).level());
        verify(profiles).prefetchForRequest(42L, question, "request");
        verify(analyzer).analyze("请总结[用户资料]处理规则");
    }
}
