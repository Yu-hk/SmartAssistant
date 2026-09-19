package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
class ProfileCommitCandidateIntegrationTest {
    final ProfileGenerationFenceIntegrationTest fixture=new ProfileGenerationFenceIntegrationTest();
    ProfileCommitCandidateStore store;
    @BeforeAll static void database() {
        ProfileGenerationFenceIntegrationTest.database();
        Path migration=Path.of("docs/database/migrations/20260919_add_profile_commit_candidates.sql");
        if (!java.nio.file.Files.exists(migration)) migration=Path.of("..").resolve(migration);
        new ResourceDatabasePopulator(new FileSystemResource(migration)).execute(ProfileGenerationFenceIntegrationTest.ds);
    }
    @BeforeEach void setup() { fixture.fixture(); store=new ProfileCommitCandidateStore(ProfileGenerationFenceIntegrationTest.jdbc,new ObjectMapper(),fixture.fence); }
    @AfterEach void cleanup() { fixture.cleanup(); }
    UserProfileService.PreparedProfileCandidate candidate() {
        return new UserProfileService.PreparedProfileCandidate(fixture.user,"candidate-fixture",0,ProfileGenerationFenceIntegrationTest.report(),"fixture private text",null,List.of(),0);
    }
    UserProfileCommitRequestedEvent stage() { var c=candidate(); return UserProfileCommitRequestedEvent.reference(c,store.stage(c)); }
    @Test void durableRoundTripAndDuplicatePublicationKeepSameReference() {
        var event=stage(); assertEquals(candidate(),store.resolve(event).orElseThrow()); assertEquals(event.candidateId(),store.stage(candidate()));
    }
    @Test void completedReceiptDoesNotRetainOrRestorePayload() {
        var event=stage(); store.retire(event); assertTrue(store.resolve(event).isEmpty());
        assertEquals(event.candidateId(),store.stage(candidate())); assertTrue(store.resolve(event).isEmpty());
    }
    @Test void otherOwnerCannotResolveOrRetire() {
        var event=stage(); var other=new UserProfileCommitRequestedEvent("2",fixture.user+100000,"candidate-fixture",null,event.requestedAt(),event.candidateId(),0L);
        assertTrue(store.resolve(other).isEmpty()); store.retire(other); assertTrue(store.resolve(event).isPresent());
    }
    @Test void wrongRequestCannotResolveOrRetire() {
        var event=stage(); var other=new UserProfileCommitRequestedEvent("2",fixture.user,"wrong-request",null,event.requestedAt(),event.candidateId(),0L);
        assertTrue(store.resolve(other).isEmpty()); store.retire(other); assertTrue(store.resolve(event).isPresent());
    }
    @Test void pauseRejectsStagingAndReadingButAllowsScopedRetirement() {
        var event=stage(); fixture.pauseAndErase();
        assertThrows(ProfileGenerationFence.Rejected.class,()->store.stage(candidate()));
        assertThrows(ProfileGenerationFence.Rejected.class,()->store.resolve(event)); store.retire(event);
        assertNull(ProfileGenerationFenceIntegrationTest.jdbc.queryForObject("SELECT payload::text FROM profile_commit_candidate WHERE candidate_id=?",String.class,event.candidateId()));
    }
    @Test void expiredPayloadIsUnavailableThenCleanupRemovesIt() {
        var event=stage(); ProfileGenerationFenceIntegrationTest.jdbc.update("UPDATE profile_commit_candidate SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE candidate_id=?",event.candidateId());
        assertTrue(store.resolve(event).isEmpty()); store.cleanupExpired();
        assertEquals(0L,ProfileGenerationFenceIntegrationTest.jdbc.queryForObject("SELECT count(*) FROM profile_commit_candidate WHERE candidate_id=?",Long.class,event.candidateId()));
    }
    @Test void mismatchedStoredIdentityIsNotTrusted() {
        var event=stage(); ProfileGenerationFenceIntegrationTest.jdbc.update("UPDATE profile_commit_candidate SET payload=jsonb_set(payload,'{userId}','0'::jsonb) WHERE candidate_id=?",event.candidateId());
        assertThrows(IllegalStateException.class,()->store.resolve(event));
    }
    @Test void freshCandidateSurvivesExpiryCleanup() {
        var event=stage(); store.cleanupExpired(); assertTrue(store.resolve(event).isPresent());
    }
}
