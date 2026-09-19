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
    static ProfileAdmissionStore admit(UserProfileService service,long generation) {
        var admission=mock(ProfileAdmissionStore.class);
        when(admission.requireExisting(anyLong(),anyString(),anyString())).thenReturn(generation);
        ReflectionTestUtils.setField(service,"admissionStore",admission);
        return admission;
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
        var service=service(extractor,store); var admission=admit(service,4L);
        service.extractAdmittedPreferences(42L,"当前问题","request");
        verify(store).save(eq(42L),eq("request"),eq(0L),any(),isNull(),eq(List.of()),eq(4L));
        verify(admission,times(1)).requireExisting(42L,"request","当前问题");
        verify(store,never()).captureGeneration(anyLong());
    }
    @Test void pausedAccountDoesNotStartAnalysis() {
        var store=mock(UserProfileSnapshotStore.class); var extractor=mock(LLMPreferenceExtractor.class);
        var service=service(extractor,store); var admission=admit(service,0L);
        when(admission.requireExisting(anyLong(),anyString(),anyString())).thenThrow(new ProfileGenerationFence.Rejected());
        assertThrows(ProfileGenerationFence.Rejected.class,()->service.extractAdmittedPreferences(42L,"问题","request"));
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
        admit(service,2L);
        ReflectionTestUtils.setField(service,"routingCallLogMapper",mapper);
        service.extractAdmittedPreferences(42L,"当前问题","request");
        verifyNoInteractions(mapper);
        verify(extractor).extract(anyString(),contains("当前问题"),eq("当前问题"));
    }
    @Test void invalidationDuringAnalysisDoesNotPublishCandidate() {
        var store=mock(UserProfileSnapshotStore.class); var extractor=mock(LLMPreferenceExtractor.class);
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values=(org.springframework.data.redis.core.ValueOperations<String,String>)mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        UserProfilePrefetchTest.stubPublication(redis);
        when(store.captureGeneration(42L)).thenReturn(3L);
        when(extractor.extract(anyString(),anyString(),anyString())).thenAnswer(call->{
            doThrow(new ProfileGenerationFence.Rejected()).when(store).requireGeneration(42L,3L); return report();
        });
        var service=service(extractor,store);
        var admission=mock(ProfileAdmissionCoordinator.class);
        when(admission.admit(42L,"fixture","当前问题")).thenReturn(java.util.OptionalLong.of(3));
        ReflectionTestUtils.setField(service,"admissionCoordinator",admission);
        var publicationFence=mock(ProfileGenerationFence.class);
        when(publicationFence.write(anyLong(),anyLong(),any())).thenAnswer(call ->
                ((java.util.function.Supplier<?>)call.getArgument(2)).get());
        ReflectionTestUtils.setField(service,"publicationFence",publicationFence);
        ReflectionTestUtils.setField(service,"redisTemplate",redis);
        ReflectionTestUtils.setField(service,"profileExecutor",(java.util.concurrent.Executor)Runnable::run);
        assertEquals("",service.prefetchForRequest(42L,"当前问题","fixture").join());
        verify(extractor).extract(anyString(),anyString(),anyString());
        verify(redis,times(1)).execute(eq(ProfileRequestRedisStore.PUBLISH),anyList(),
                eq("42|3"),eq(com.example.smartassistant.routing.contract.RoutingKeys.USER_PROFILE_PENDING),
                eq(""),eq("0"),anyString(),eq("fixture"),eq("3"));
        verify(redis,never()).execute(eq(ProfileRequestRedisStore.PUBLISH),anyList(),
                anyString(),anyString(),anyString(),eq("1"),anyString(),anyString(),anyString());
    }
    @Test void delayedPreparationKeepsAdmissionGenerationAndCannotStartAfterReset() {
        var store=mock(UserProfileSnapshotStore.class); var extractor=mock(LLMPreferenceExtractor.class);
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var admission=mock(ProfileAdmissionCoordinator.class);
        when(admission.admit(42L,"delayed","question")).thenReturn(java.util.OptionalLong.of(3));
        var service=service(extractor,store);
        var task=new java.util.concurrent.atomic.AtomicReference<Runnable>();
        ReflectionTestUtils.setField(service,"admissionCoordinator",admission);
        ReflectionTestUtils.setField(service,"redisTemplate",redis);
        ReflectionTestUtils.setField(service,"profileExecutor",(java.util.concurrent.Executor)task::set);
        var result=service.prefetchForRequest(42L,"question","delayed");
        assertFalse(result.isDone());
        doThrow(new ProfileGenerationFence.Rejected()).when(store).requireGeneration(42L,3L);
        task.get().run();
        assertEquals("",result.join());
        verifyNoInteractions(extractor,redis);
        verify(store,never()).captureGeneration(anyLong());
        verify(admission,times(1)).admit(42L,"delayed","question");
    }
    @Test void failedAdmissionDoesNotScheduleModelOrRedisWork() {
        var store=mock(UserProfileSnapshotStore.class); var extractor=mock(LLMPreferenceExtractor.class);
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var admission=mock(ProfileAdmissionCoordinator.class); var executor=mock(java.util.concurrent.Executor.class);
        when(admission.admit(42L,"busy","question")).thenReturn(java.util.OptionalLong.empty());
        var service=service(extractor,store);
        ReflectionTestUtils.setField(service,"admissionCoordinator",admission);
        ReflectionTestUtils.setField(service,"redisTemplate",redis);
        ReflectionTestUtils.setField(service,"profileExecutor",executor);
        assertEquals("",service.prefetchForRequest(42L,"question","busy").join());
        verifyNoInteractions(store,extractor,redis,executor);
    }
    @Test void legacyEntryCannotRecaptureOrAnalyze() {
        var store=mock(UserProfileSnapshotStore.class);var extractor=mock(LLMPreferenceExtractor.class);
        var service=service(extractor,store);
        assertThrows(ProfileGenerationFence.Rejected.class,()->service.extractAndUpdatePreferences(42L,"old",null));
        verifyNoInteractions(store,extractor);
    }
}
