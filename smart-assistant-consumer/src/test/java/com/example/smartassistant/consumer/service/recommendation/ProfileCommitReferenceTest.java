package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProfileCommitReferenceTest {
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    final ProfileCommitCandidateStore store=mock(ProfileCommitCandidateStore.class);
    final UserProfileService service=mock(UserProfileService.class);
    final UserProfileCommitListener listener=new UserProfileCommitListener(json,service,store);
    final UserProfileService.PreparedProfileCandidate candidate=new UserProfileService.PreparedProfileCandidate(
            42L,"ref-fixture",0,ProfileGenerationServiceTest.report(),"private text",null,java.util.List.of(),3);
    final UserProfileCommitRequestedEvent event=UserProfileCommitRequestedEvent.reference(candidate,"11111111-1111-1111-1111-111111111111");
    void receive(UserProfileCommitRequestedEvent e) throws Exception { listener.receive(MessageBuilder.withBody(json.writeValueAsBytes(e)).build()); }

    @Test void referenceResolvesCommitsThenRetires() throws Exception {
        when(store.resolve(event)).thenReturn(Optional.of(candidate)); receive(event);
        var order=inOrder(store,service); order.verify(store).resolve(event); order.verify(service).commitPreparedProfile(candidate); order.verify(store).retire(event);
    }
    @Test void missingOrExpiredCandidateAcknowledgesWithoutReanalysis() throws Exception {
        when(store.resolve(event)).thenReturn(Optional.empty()); receive(event); verifyNoInteractions(service); verify(store).retire(event);
    }
    @Test void staleGenerationAcknowledgesAndRetires() throws Exception {
        when(store.resolve(event)).thenThrow(new ProfileGenerationFence.Rejected()); receive(event); verifyNoInteractions(service); verify(store).retire(event);
    }
    @Test void databaseFailureRetriesWithoutDestroyingPayload() {
        when(store.resolve(event)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->receive(event)); verify(store,never()).retire(any()); verifyNoInteractions(service);
    }
    @Test void commitFailurePreservesCandidateForRedelivery() {
        when(store.resolve(event)).thenReturn(Optional.of(candidate)); doThrow(new IllegalStateException("fixture")).when(service).commitPreparedProfile(candidate);
        assertThrows(IllegalStateException.class,()->receive(event)); verify(store,never()).retire(any());
    }
    @Test void malformedReferenceDoesNotReadStorage() {
        var bad=new UserProfileCommitRequestedEvent("2",42L,"ref-fixture",null,event.requestedAt(),event.candidateId(),null);
        assertThrows(IllegalArgumentException.class,()->receive(bad)); verifyNoInteractions(store,service);
    }
    @Test void v2CannotCarryEmbeddedCandidate() {
        var bad=new UserProfileCommitRequestedEvent("2",42L,"ref-fixture",candidate,event.requestedAt(),event.candidateId(),3L);
        assertThrows(IllegalArgumentException.class,()->receive(bad)); verifyNoInteractions(store,service);
    }
    @Test void failureToStageNeverSendsToBroker() {
        var rabbit=mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        var publisher=new UserProfileCommitPublisher(rabbit,json,store,"exchange","route",100);
        when(store.stage(candidate)).thenThrow(new ProfileGenerationFence.Rejected());
        assertThrows(ProfileGenerationFence.Rejected.class,()->publisher.publish(candidate)); verifyNoInteractions(rabbit);
    }
}
