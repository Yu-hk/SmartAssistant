package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.consumer.entity.RoutingCallLog;
import com.example.smartassistant.consumer.mapper.RoutingCallLogMapper;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfilePrefetchTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishesPendingCandidateAndReadyWithoutWritingDatabase() throws Exception {
        LLMPreferenceExtractor extractor = mock(LLMPreferenceExtractor.class);
        UserProfileSnapshotStore store = mock(UserProfileSnapshotStore.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(store.load(42L)).thenReturn(Optional.empty());
        stubPublication(redis);

        var report = report("CREATE", "深度咨询", 65, List.of("便携"), List.of("价格"));
        String reportJson = objectMapper.writeValueAsString(report);
        when(extractor.extract(anyString(), anyString(), eq("帮我查热门商品"))).thenReturn(report);

        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        Executor deferredExecutor = scheduled::set;
        UserProfileService service = service(extractor, store);
        ReflectionTestUtils.setField(service, "prefetchTtlSeconds", 120L);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "profileExecutor", deferredExecutor);

        CompletableFuture<String> result = service.prefetchForRequest(
                42L, "帮我查热门商品", "request-profile");

        assertThat(result).isNotDone();
        org.mockito.Mockito.verifyNoInteractions(redis, values, store, extractor);

        scheduled.get().run();

        assertThat(result.join()).isEmpty(); // Completed/cached futures must not retain profile bodies.
        assertThat(service.prefetchForRequest(42L, "帮我查热门商品", "request-profile"))
                .isSameAs(result);
        ArgumentCaptor<String> states = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> candidates = ArgumentCaptor.forClass(String.class);
        verify(redis, times(2)).execute(eq(ProfileRequestRedisStore.PUBLISH),
                eq(ProfileRequestRedisStore.keys(42L, "request-profile")), eq("42|0"),
                states.capture(), candidates.capture(), anyString(), eq("120000"), eq("request-profile"), eq("0"));
        assertThat(states.getAllValues().get(1))
                .startsWith(RoutingKeys.USER_PROFILE_READY_PREFIX)
                .contains("【电商用户洞察】");
        assertThat(candidates.getAllValues().getFirst()).isEmpty();
        assertThat(candidates.getAllValues().getLast()).contains("request-profile", "深度咨询");
        verify(store, never()).save(anyLong(), anyString(), anyLong(), any(), any(), anyList(), anyLong());
    }

    @Test
    void missingCoordinationStoreSkipsOptionalProfile() {
        UserProfileService service = service(
                mock(LLMPreferenceExtractor.class), mock(UserProfileSnapshotStore.class));

        assertThat(service.prefetchForRequest(42L, "推荐手机", "request-no-redis").join()).isEmpty();
    }

    @Test
    void createsAndThenUpdatesVersionedDatabaseProfile() throws Exception {
        LLMPreferenceExtractor extractor = mock(LLMPreferenceExtractor.class);
        UserProfileSnapshotStore store = mock(UserProfileSnapshotStore.class);
        RoutingCallLogMapper historyMapper = mock(RoutingCallLogMapper.class);
        RoutingCallLog newer = RoutingCallLog.builder().id(2L).userInput("预算五千元").build();
        RoutingCallLog older = RoutingCallLog.builder().id(1L).userInput("主要用于出差办公").build();
        when(historyMapper.findRecentByUserId(42L, 20)).thenReturn(List.of(newer, older));

        AtomicReference<UserProfileSnapshotStore.Snapshot> current = new AtomicReference<>();
        when(store.load(42L)).thenAnswer(invocation -> Optional.ofNullable(current.get()));
        when(store.save(eq(42L), nullable(String.class), anyLong(), any(), any(), anyList(), eq(0L)))
                .thenAnswer(invocation -> {
                    long expected = invocation.getArgument(2);
                    LLMPreferenceExtractor.UserInsightReport report = invocation.getArgument(3);
                    var saved = snapshot(42L, expected + 1,
                            objectMapper.writeValueAsString(report));
                    current.set(saved);
                    return saved;
                });

        UserProfileService service = service(extractor, store);
        ReflectionTestUtils.setField(service, "routingCallLogMapper", historyMapper);

        when(extractor.extract(anyString(), anyString(), eq("我喜欢轻薄电脑")))
                .thenReturn(report("CREATE", "深度咨询", 65,
                        List.of("便携办公"), List.of("重量顾虑")));
        service.extractAdmittedPreferences(42L, "我喜欢轻薄电脑", "first");

        assertThat(service.buildUserProfilePrompt(42L))
                .contains("深度咨询", "便携办公", "重量顾虑")
                .doesNotContain("我喜欢轻薄电脑");
        ArgumentCaptor<String> initialProfile = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> history = ArgumentCaptor.forClass(String.class);
        verify(extractor).extract(initialProfile.capture(), history.capture(),
                eq("我喜欢轻薄电脑"));
        assertThat(initialProfile.getValue()).contains("当前没有已保存画像");
        assertThat(history.getValue())
                .containsSubsequence("主要用于出差办公", "预算五千元", "我喜欢轻薄电脑");

        when(extractor.extract(anyString(), anyString(), eq("售后没问题我就下单")))
                .thenReturn(report("UPDATE", "临门一脚", 88,
                        List.of("购买意愿明确"), List.of("售后顾虑")));
        service.extractAdmittedPreferences(42L, "售后没问题我就下单", "second");

        assertThat(current.get().profileVersion()).isEqualTo(2L);
        assertThat(current.get().reportJson())
                .contains("临门一脚", "售后顾虑")
                .doesNotContain("深度咨询");
        ArgumentCaptor<String> updateBase = ArgumentCaptor.forClass(String.class);
        verify(extractor).extract(updateBase.capture(), anyString(), eq("售后没问题我就下单"));
        assertThat(updateBase.getValue()).contains("深度咨询");
    }

    @Test
    void successfulTurnPublishesCommitAndListenerEntryPersistsPreparedCandidate() throws Exception {
        LLMPreferenceExtractor extractor = mock(LLMPreferenceExtractor.class);
        UserProfileSnapshotStore store = mock(UserProfileSnapshotStore.class);
        UserProfileCommitPublisher publisher = mock(UserProfileCommitPublisher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        var insight = report("CREATE", "深度咨询", 72,
                List.of("便携办公"), List.of("售后顾虑"));
        var candidate = new UserProfileService.PreparedProfileCandidate(
                42L, "request-commit", 0L, insight,
                "我想买轻薄电脑",
                9L, List.of(7L, 9L));
        when(values.get(RoutingKeys.userProfileCandidate("request-commit")))
                .thenReturn(objectMapper.writeValueAsString(candidate));
        when(store.isRequestApplied(42L, "request-commit")).thenReturn(false);
        when(store.save(eq(42L), eq("request-commit"), eq(0L), eq(insight),
                eq(9L), eq(List.of(7L, 9L)), eq(0L)))
                .thenReturn(snapshot(42L, 1L, objectMapper.writeValueAsString(insight)));

        UserProfileService service = service(extractor, store, publisher);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "prefetchTtlSeconds", 120L);
        ReflectionTestUtils.setField(service, "commitWaitTimeoutMs", 1_000L);
        ReflectionTestUtils.setField(service, "commitPollIntervalMs", 1L);

        service.commitAfterSuccessfulTurn(42L, "request-commit");
        verify(publisher).publish(candidate);
        verify(redis).execute(eq(ProfileRequestRedisStore.RETIRE),
                eq(List.of(ProfileRequestRedisStore.ownerKey("request-commit"), RoutingKeys.userProfileCandidate("request-commit"))),
                eq("42|0"), eq(objectMapper.writeValueAsString(candidate)));

        // The durable MQ payload is sufficient for persistence even after Redis is gone.
        ReflectionTestUtils.setField(service, "redisTemplate", null);
        service.commitPreparedProfile(candidate);

        verify(store).save(42L, "request-commit", 0L, insight,
                9L, List.of(7L, 9L), 0L);
    }

    @Test
    void commitWaitFailsWhenPreparedCandidateIsStillPending() {
        UserProfileSnapshotStore store = mock(UserProfileSnapshotStore.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RoutingKeys.userProfileCandidate("request-pending"))).thenReturn(null);
        when(values.get(RoutingKeys.userProfileContext("request-pending")))
                .thenReturn(RoutingKeys.USER_PROFILE_PENDING);
        UserProfileService service = service(
                mock(LLMPreferenceExtractor.class), store,
                mock(UserProfileCommitPublisher.class));
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "commitWaitTimeoutMs", 1L);
        ReflectionTestUtils.setField(service, "commitPollIntervalMs", 1L);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service, "awaitPreparedCandidate", 42L, "request-pending"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit timeout");
        verify(store, never()).save(anyLong(), anyString(), anyLong(), any(), any(), anyList(), anyLong());
    }

    @Test
    void commitReanalyzesAgainstLatestSnapshotAfterVersionConflict() throws Exception {
        LLMPreferenceExtractor extractor = mock(LLMPreferenceExtractor.class);
        UserProfileSnapshotStore store = mock(UserProfileSnapshotStore.class);
        var stale = report("UPDATE", "深度咨询", 70,
                List.of("关注性能"), List.of("价格顾虑"));
        var current = report("UPDATE", "深度咨询", 74,
                List.of("重视售后"), List.of("物流顾虑"));
        var rebased = report("UPDATE", "临门一脚", 86,
                List.of("购买意愿明确"), List.of("价格顾虑"));
        var candidate = new UserProfileService.PreparedProfileCandidate(
                42L, "request-conflict", 1L, stale,
                "预算合适就买", 20L, List.of(18L, 20L));
        when(store.isRequestApplied(42L, "request-conflict")).thenReturn(false);
        when(store.save(42L, "request-conflict", 1L, stale,
                20L, List.of(18L, 20L), 0L))
                .thenThrow(new UserProfileSnapshotStore.OptimisticProfileUpdateException(
                        42L, 1L, 2L));
        when(store.load(42L)).thenReturn(Optional.of(snapshot(
                42L, 2L, objectMapper.writeValueAsString(current))));
        when(extractor.extract(anyString(), eq("[当前用户消息]\n预算合适就买"),
                eq("预算合适就买"))).thenReturn(rebased);
        when(store.save(42L, "request-conflict", 2L, rebased,
                20L, List.of(18L, 20L), 0L))
                .thenReturn(snapshot(42L, 3L, objectMapper.writeValueAsString(rebased)));
        UserProfileService service = service(
                extractor, store, mock(UserProfileCommitPublisher.class));

        service.commitPreparedProfile(candidate);

        verify(extractor).extract(org.mockito.ArgumentMatchers.contains("重视售后"),
                eq("[当前用户消息]\n预算合适就买"), eq("预算合适就买"));
        verify(store).save(42L, "request-conflict", 2L, rebased,
                20L, List.of(18L, 20L), 0L);
    }

    @Test
    void existingReliableSnapshotIsPublishedBeforeSlowAnalysisAndSurvivesEmptyResult() throws Exception {
        var extractor = mock(LLMPreferenceExtractor.class);
        var store = mock(UserProfileSnapshotStore.class);
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var previous = report("CREATE", "深度咨询", 70, List.of("轻薄"), List.of("预算"));
        stubPublication(redis);
        when(store.load(42L)).thenReturn(Optional.of(snapshot(42L, 3L, objectMapper.writeValueAsString(previous))));
        when(extractor.extract(anyString(), anyString(), anyString())).thenAnswer(ignored -> {
            verify(redis).execute(eq(ProfileRequestRedisStore.PUBLISH), eq(ProfileRequestRedisStore.keys(42L, "saved")),
                    eq("42|0"), org.mockito.ArgumentMatchers.startsWith(RoutingKeys.USER_PROFILE_READY_PREFIX),
                    eq(""), eq("0"), anyString(), eq("saved"), eq("0"));
            return LLMPreferenceExtractor.UserInsightReport.empty("用户画像分析超时");
        });
        UserProfileService service = service(extractor, store);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "profileExecutor", (Executor) Runnable::run);
        assertThat(service.prefetchForRequest(42L, "本轮预算2000", "saved").join())
                .isEmpty();
        verify(redis).execute(eq(ProfileRequestRedisStore.PUBLISH),
                eq(ProfileRequestRedisStore.keys(42L, "saved")), eq("42|0"),
                org.mockito.ArgumentMatchers.contains("轻薄"), eq(""), eq("1"),
                anyString(), eq("saved"), eq("0"));
        verify(redis, never()).execute(eq(ProfileRequestRedisStore.PUBLISH), anyList(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.matches(".+"), anyString(), anyString(), anyString(), anyString());
        verify(store, never()).save(anyLong(), anyString(), anyLong(), any(), any(), anyList(), anyLong());
        // Same request is deduplicated, not reset to PENDING or analyzed a second time.
        service.prefetchForRequest(42L, "本轮预算2000", "saved").join();
        verify(extractor, times(1)).extract(anyString(), anyString(), anyString());
    }

    @Test
    void rejectedPreparationNeverTouchesStorageOnCaller() {
        var extractor = mock(LLMPreferenceExtractor.class);
        var store = mock(UserProfileSnapshotStore.class);
        var redis = mock(StringRedisTemplate.class);
        UserProfileService service = service(extractor, store);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "profileExecutor", (Executor) task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        assertThat(service.prefetchForRequest(42L, "买平板", "busy").join()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(redis, store, extractor);
    }

    @Test
    void storageFailureInWorkerReturnsEmptyInsteadOfFailingChat() {
        var extractor = mock(LLMPreferenceExtractor.class);
        var store = mock(UserProfileSnapshotStore.class);
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(ProfileRequestRedisStore.PUBLISH), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("offline"));
        UserProfileService service = service(extractor, store);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "profileExecutor", (Executor) Runnable::run);
        assertThat(service.prefetchForRequest(42L, "买平板", "unavailable").join()).isEmpty();
        verify(store, times(2)).requireGeneration(42L, 0L);
        verify(store, never()).captureGeneration(anyLong());
        org.mockito.Mockito.verifyNoInteractions(extractor);
    }

    @Test
    void successfulTurnCommitIsScheduledWithoutSpringAsyncProxy() {
        var publisher = mock(UserProfileCommitPublisher.class);
        var redis = mock(StringRedisTemplate.class);
        var service = service(mock(LLMPreferenceExtractor.class), mock(UserProfileSnapshotStore.class), publisher);
        AtomicReference<Runnable> task = new AtomicReference<>();
        ReflectionTestUtils.setField(service, "commitExecutor", (Executor) task::set);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        service.commitAfterSuccessfulTurn(42L, "pending");
        assertThat(task.get()).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(redis, publisher);
    }

    @Test
    void emptyKeepCandidateDoesNotOverwriteExistingSnapshotEvenFromOldQueueMessage() {
        var store = mock(UserProfileSnapshotStore.class);
        var service = service(mock(LLMPreferenceExtractor.class), store);
        service.commitPreparedProfile(new UserProfileService.PreparedProfileCandidate(42L, "timeout", 2L,
                LLMPreferenceExtractor.UserInsightReport.empty("用户画像分析超时"), "推荐商品", 3L, List.of(3L)));
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void lateCandidatePublishedBetweenPollingReadsIsStillCommitted() throws Exception {
        var publisher = mock(UserProfileCommitPublisher.class);
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var candidate = new UserProfileService.PreparedProfileCandidate(42L, "late", 0L,
                report("CREATE", "深度咨询", 70, List.of("便携"), List.of("预算")), "选电脑", 1L, List.of(1L));
        when(values.get(RoutingKeys.userProfileCandidate("late")))
                .thenReturn(null, objectMapper.writeValueAsString(candidate));
        when(values.get(ProfileRequestRedisStore.doneKey("late"))).thenReturn("DONE");
        var service = service(mock(LLMPreferenceExtractor.class), mock(UserProfileSnapshotStore.class), publisher);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        service.commitAfterSuccessfulTurn(42L, "late");
        verify(publisher).publish(candidate);
    }

    static void stubPublication(StringRedisTemplate redis) {
        when(redis.execute(eq(ProfileRequestRedisStore.PUBLISH), anyList(), any(Object[].class))).thenReturn(1L);
    }

    private static UserProfileService service(
            LLMPreferenceExtractor extractor, UserProfileSnapshotStore store) {
        return service(extractor, store, mock(UserProfileCommitPublisher.class));
    }

    private static UserProfileService service(
            LLMPreferenceExtractor extractor, UserProfileSnapshotStore store,
            UserProfileCommitPublisher publisher) {
        UserProfileService service = new UserProfileService(extractor, store, publisher);
        ReflectionTestUtils.setField(service, "admissionStore", mock(ProfileAdmissionStore.class));
        var admission = mock(ProfileAdmissionCoordinator.class);
        when(admission.admit(anyLong(), anyString(), anyString())).thenReturn(java.util.OptionalLong.of(0));
        ReflectionTestUtils.setField(service, "admissionCoordinator", admission);
        var publicationFence = mock(ProfileGenerationFence.class);
        when(publicationFence.write(anyLong(),anyLong(),any())).thenAnswer(call ->
                ((java.util.function.Supplier<?>) call.getArgument(2)).get());
        ReflectionTestUtils.setField(service, "publicationFence", publicationFence);
        ReflectionTestUtils.setField(service, "maxHistoryTurns", 20);
        ReflectionTestUtils.setField(service, "maxHistoryChars", 12000);
        ReflectionTestUtils.setField(service, "commitExecutor", (Executor) Runnable::run);
        return service;
    }

    private static UserProfileSnapshotStore.Snapshot snapshot(
            Long userId, long version, String reportJson) {
        return new UserProfileSnapshotStore.Snapshot(userId, version,
                UserProfileSnapshotStore.SCHEMA_VERSION, reportJson, 2L,
                Instant.now(), Instant.now());
    }

    private static LLMPreferenceExtractor.UserInsightReport report(
            String action, String stage, int intentScore,
            List<String> drivers, List<String> barriers) {
        return new LLMPreferenceExtractor.UserInsightReport(
                new LLMPreferenceExtractor.ProfileUpdate(action,
                        List.of("commerceAssessment", "topDrivers", "topBarriers"),
                        List.of(), "对话提供了新的购买证据", List.of("用户原话")),
                Map.of("purchaseMotivation", new LLMPreferenceExtractor.InsightDimension(
                        drivers.getFirst(), List.of("我喜欢轻薄电脑"), "中")),
                List.of(), Map.of("购买意愿", 7, "价格敏感度", 5),
                drivers, barriers,
                new LLMPreferenceExtractor.CommerceAssessment(
                        true, stage, "审慎型", "中", intentScore, "中",
                        barriers, "当前", false, List.of(), List.of()),
                List.of(new LLMPreferenceExtractor.ConversionStrategy(
                        barriers.getFirst(), "提供事实", "说明售后政策", "降低顾虑", "高")));
    }
}
