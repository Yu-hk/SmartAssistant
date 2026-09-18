package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.consumer.mapper.RoutingCallLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProfileGenerationServiceTest {
    static LLMPreferenceExtractor.UserInsightReport report() {
        return new LLMPreferenceExtractor.UserInsightReport(
                new LLMPreferenceExtractor.ProfileUpdate("CREATE", List.of("commerceAssessment"), List.of(), "fixture", List.of()),
                Map.of(), List.of(), Map.of(), List.of(), List.of(),
                new LLMPreferenceExtractor.CommerceAssessment(true, "深度咨询", "审慎型", "中", 75, "低",
                        List.of(), "当前", false, List.of(), List.of()), List.of());
    }
    static UserProfileService service(LLMPreferenceExtractor extractor, UserProfileSnapshotStore store) {
        return new UserProfileService(extractor, store, mock(UserProfileCommitPublisher.class));
    }
    @Test void legacyJsonKeepsGenerationZero() throws Exception {
        var json = new ObjectMapper();
        var tree = json.valueToTree(new UserProfileService.PreparedProfileCandidate(42L,"old",0,report(),"new",null,List.of()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("generation");
        assertEquals(0L, json.treeToValue(tree, UserProfileService.PreparedProfileCandidate.class).generation());
    }
    @Test void generationCapturedBeforeModelIsNotRecapturedWhenItChanges() {
        var store=mock(UserProfileSnapshotStore.class); var extractor=mock(LLMPreferenceExtractor.class);
        when(store.captureGeneration(42L)).thenReturn(4L);
        when(extractor.extract(anyString(),anyString(),anyString())).thenAnswer(call -> {
            when(store.captureGeneration(42L)).thenReturn(5L); return report();
        });
        service(extractor,store).extractAndUpdatePreferences(42L,"当前问题",null);
        verify(store).save(eq(42L),isNull(),eq(0L),any(),isNull(),eq(List.of()),eq(4L));
        verify(store,times(1)).captureGeneration(42L);
    }
    @Test void pausedAccountDoesNotStartAnalysis() {
        var store=mock(UserProfileSnapshotStore.class); var extractor=mock(LLMPreferenceExtractor.class);
        when(store.captureGeneration(42L)).thenThrow(new ProfileGenerationFence.Rejected());
        assertThrows(ProfileGenerationFence.Rejected.class,()->service(extractor,store).extractAndUpdatePreferences(42L,"问题",null));
        verifyNoInteractions(extractor);
    }
    @Test void invalidatedMqCandidateIsAcknowledgedWithoutReanalysis() {
        var store=mock(UserProfileSnapshotStore.class); var extractor=mock(LLMPreferenceExtractor.class);
        when(store.save(anyLong(),anyString(),anyLong(),any(),any(),anyList(),anyLong()))
                .thenThrow(new ProfileGenerationFence.Rejected());
        service(extractor,store).commitPreparedProfile(new UserProfileService.PreparedProfileCandidate(42L,"stale",0,report(),"old",null,List.of(),3));
        verifyNoInteractions(extractor);
        verify(store,times(1)).save(anyLong(),anyString(),anyLong(),any(),any(),anyList(),eq(3L));
    }
    @Test void invalidationDuringVersionConflictDoesNotRebaseIntoNewGeneration() {
        var store=mock(UserProfileSnapshotStore.class); var extractor=mock(LLMPreferenceExtractor.class);
        when(store.save(anyLong(),anyString(),anyLong(),any(),any(),anyList(),anyLong()))
                .thenThrow(new UserProfileSnapshotStore.OptimisticProfileUpdateException(42L,0,1));
        doThrow(new ProfileGenerationFence.Rejected()).when(store).requireGeneration(42L,3L);
        service(extractor,store).commitPreparedProfile(new UserProfileService.PreparedProfileCandidate(42L,"stale",0,report(),"old",null,List.of(),3));
        verifyNoInteractions(extractor);
        verify(store,never()).load(anyLong());
    }
    @Test void resetGenerationDoesNotReadUnversionedChatHistory() {
        var store=mock(UserProfileSnapshotStore.class); var extractor=mock(LLMPreferenceExtractor.class);
        when(store.captureGeneration(42L)).thenReturn(2L);
        when(extractor.extract(anyString(),anyString(),anyString())).thenReturn(report());
        var service=service(extractor,store); var mapper=mock(RoutingCallLogMapper.class);
        ReflectionTestUtils.setField(service,"routingCallLogMapper",mapper);
        service.extractAndUpdatePreferences(42L,"当前问题",null);
        verifyNoInteractions(mapper);
        verify(extractor).extract(anyString(),contains("当前问题"),eq("当前问题"));
    }
    @Test void invalidationDuringAnalysisDoesNotPublishCandidate() {
        var store=mock(UserProfileSnapshotStore.class); var extractor=mock(LLMPreferenceExtractor.class);
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values=(org.springframework.data.redis.core.ValueOperations<String,String>)mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(store.captureGeneration(42L)).thenReturn(3L);
        when(extractor.extract(anyString(),anyString(),anyString())).thenAnswer(call->{
            doThrow(new ProfileGenerationFence.Rejected()).when(store).requireGeneration(42L,3L); return report();
        });
        var service=service(extractor,store);
        ReflectionTestUtils.setField(service,"redisTemplate",redis);
        ReflectionTestUtils.setField(service,"profileExecutor",(java.util.concurrent.Executor)Runnable::run);
        assertEquals("",service.prefetchForRequest(42L,"当前问题","fixture").join());
        verify(values,never()).set(eq(com.example.smartassistant.routing.contract.RoutingKeys.userProfileCandidate("fixture")),anyString(),any(java.time.Duration.class));
    }
}
