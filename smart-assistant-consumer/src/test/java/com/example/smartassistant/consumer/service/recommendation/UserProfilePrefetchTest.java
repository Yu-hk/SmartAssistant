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

        var report = LLMPreferenceExtractor.UserInsightReport.empty("信息不足");
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
        verify(values).set(eq(RoutingKeys.userProfileContext("request-profile")),
                eq(RoutingKeys.USER_PROFILE_PENDING), eq(Duration.ofSeconds(120)));

        scheduled.get().run();

        assertThat(result.join()).contains("【电商用户洞察】").contains("信息不足");
        ArgumentCaptor<String> states = ArgumentCaptor.forClass(String.class);
        verify(values, times(2)).set(eq(RoutingKeys.userProfileContext("request-profile")),
                states.capture(), any(Duration.class));
        assertThat(states.getAllValues().get(1))
                .startsWith(RoutingKeys.USER_PROFILE_READY_PREFIX)
                .contains("【电商用户洞察】");
        verify(values).set(eq(RoutingKeys.userProfileCandidate("request-profile")),
                anyString(), eq(Duration.ofSeconds(120)));
        verify(store, never()).save(anyLong(), anyString(), anyLong(), any(), any(), anyList());
    }

    @Test
    void failsClosedWhenCoordinationStoreIsUnavailable() {
        UserProfileService service = service(
                mock(LLMPreferenceExtractor.class), mock(UserProfileSnapshotStore.class));

        assertThatThrownBy(() -> service.prefetchForRequest(42L, "推荐手机", "request-no-redis"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires Redis");
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
        when(store.save(eq(42L), nullable(String.class), anyLong(), any(), any(), anyList()))
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
        service.extractAndUpdatePreferences(42L, "我喜欢轻薄电脑", null);

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
        service.extractAndUpdatePreferences(42L, "售后没问题我就下单", null);

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
                eq(9L), eq(List.of(7L, 9L))))
                .thenReturn(snapshot(42L, 1L, objectMapper.writeValueAsString(insight)));

        UserProfileService service = service(extractor, store, publisher);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "prefetchTtlSeconds", 120L);
        ReflectionTestUtils.setField(service, "commitWaitTimeoutMs", 1_000L);
        ReflectionTestUtils.setField(service, "commitPollIntervalMs", 1L);

        service.commitAfterSuccessfulTurn(42L, "request-commit");
        verify(publisher).publish(candidate);
        verify(redis).delete(RoutingKeys.userProfileCandidate("request-commit"));

        // The durable MQ payload is sufficient for persistence even after Redis is gone.
        ReflectionTestUtils.setField(service, "redisTemplate", null);
        service.commitPreparedProfile(candidate);

        verify(store).save(42L, "request-commit", 0L, insight,
                9L, List.of(7L, 9L));
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
        verify(store, never()).save(anyLong(), anyString(), anyLong(), any(), any(), anyList());
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
                20L, List.of(18L, 20L)))
                .thenThrow(new UserProfileSnapshotStore.OptimisticProfileUpdateException(
                        42L, 1L, 2L));
        when(store.load(42L)).thenReturn(Optional.of(snapshot(
                42L, 2L, objectMapper.writeValueAsString(current))));
        when(extractor.extract(anyString(), eq("[当前用户消息]\n预算合适就买"),
                eq("预算合适就买"))).thenReturn(rebased);
        when(store.save(42L, "request-conflict", 2L, rebased,
                20L, List.of(18L, 20L)))
                .thenReturn(snapshot(42L, 3L, objectMapper.writeValueAsString(rebased)));
        UserProfileService service = service(
                extractor, store, mock(UserProfileCommitPublisher.class));

        service.commitPreparedProfile(candidate);

        verify(extractor).extract(org.mockito.ArgumentMatchers.contains("重视售后"),
                eq("[当前用户消息]\n预算合适就买"), eq("预算合适就买"));
        verify(store).save(42L, "request-conflict", 2L, rebased,
                20L, List.of(18L, 20L));
    }

    private static UserProfileService service(
            LLMPreferenceExtractor extractor, UserProfileSnapshotStore store) {
        return service(extractor, store, mock(UserProfileCommitPublisher.class));
    }

    private static UserProfileService service(
            LLMPreferenceExtractor extractor, UserProfileSnapshotStore store,
            UserProfileCommitPublisher publisher) {
        UserProfileService service = new UserProfileService(extractor, store, publisher);
        ReflectionTestUtils.setField(service, "maxHistoryTurns", 20);
        ReflectionTestUtils.setField(service, "maxHistoryChars", 12000);
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
