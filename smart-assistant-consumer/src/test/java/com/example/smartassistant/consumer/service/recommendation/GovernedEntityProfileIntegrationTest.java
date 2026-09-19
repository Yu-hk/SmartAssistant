package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
class GovernedEntityProfileIntegrationTest {
    final ProfileGenerationFenceIntegrationTest fixture=new ProfileGenerationFenceIntegrationTest();
    GovernedEntityProfileStore store;
    @BeforeAll static void database() {
        ProfileAdmissionIntegrationTest.database();
        Path path=Path.of("docs/database/migrations/20260919_add_profile_entity_facts.sql");
        if(!java.nio.file.Files.exists(path)) path=Path.of("..").resolve(path);
        new ResourceDatabasePopulator(new FileSystemResource(path)).execute(ProfileGenerationFenceIntegrationTest.ds);
    }
    @BeforeEach void setup() { fixture.fixture();store=new GovernedEntityProfileStore(ProfileGenerationFenceIntegrationTest.jdbc,fixture.fence); }
    @AfterEach void cleanup() { fixture.cleanup(); }
    JdbcTemplate db() { return ProfileGenerationFenceIntegrationTest.jdbc; }
    void save(long generation) { store.save(fixture.user,generation,Map.of("preference","便携")); }
    @Test void generationZeroRoundTripAndUpdate() {
        assertEquals(0,store.capture(fixture.user));
        new ProfileAdmissionStore(db(),fixture.fence).admit(fixture.user,"entity-request","我喜欢便携");
        // Exercise the production bean factory: it must not instantiate the legacy Redis writer.
        new com.example.smartassistant.consumer.rag.EntityProfileConfig().entityProfileService(store)
                .extractAndStore(fixture.user,"我喜欢便携","","entity-request");
        assertEquals(Map.of("preference","便携"),store.read(fixture.user));
        store.save(fixture.user,0,Map.of("preference","轻薄"));
        assertEquals(Map.of("preference","轻薄"),store.read(fixture.user));
        assertEquals(1L,db().queryForObject("SELECT count(*) FROM user_profile_entity_fact WHERE user_id=?",Long.class,fixture.user));
    }
    @Test void pausedAccountSuppressesReadAndRejectsWrite() {
        save(0);fixture.pauseAndErase();
        assertTrue(store.read(fixture.user).isEmpty());
        assertThrows(ProfileGenerationFence.Rejected.class,()->save(0));
        assertThrows(ProfileGenerationFence.Rejected.class,()->store.capture(fixture.user));
    }
    @Test void reenableCannotReadOrRewriteOldGeneration() {
        save(0);fixture.pauseAndErase();
        db().update("UPDATE profile_lifecycle SET analysis_enabled=true WHERE user_id=?",fixture.user);
        assertTrue(store.read(fixture.user).isEmpty()); assertThrows(ProfileGenerationFence.Rejected.class,()->save(0));
        store.save(fixture.user,1,Map.of("hobby","音乐"));
        assertEquals(Map.of("hobby","音乐"),store.read(fixture.user));
    }
    @Test void anotherUserCannotReadFacts() { save(0);assertTrue(store.read(fixture.user+100000).isEmpty()); }
    @Test void invalidBatchDoesNotPartiallyPersist() {
        var facts=new LinkedHashMap<String,String>();facts.put("name","测试");facts.put("is_admin","true");
        assertThrows(IllegalArgumentException.class,()->store.save(fixture.user,0,facts));
        assertTrue(store.read(fixture.user).isEmpty());
        assertThrows(IllegalArgumentException.class,()->store.save(fixture.user,0,Map.of("name","x\ny")));
    }
    @Test void failureInSecondWriteRollsBackFirstFact() {
        var failing=new JdbcTemplate(ProfileGenerationFenceIntegrationTest.ds) {
            int writes;
            @Override public int update(String sql,Object... args) {
                if(++writes==2) throw new IllegalStateException("fixture failure");
                return super.update(sql,args);
            }
        };
        var tested=new GovernedEntityProfileStore(failing,fixture.fence);
        assertThrows(IllegalStateException.class,()->tested.save(fixture.user,0,Map.of("name","测试","hobby","音乐")));
        assertTrue(store.read(fixture.user).isEmpty());
    }
    @Test void expiredFactsAreUnreadableAndCleanupIsPhysical() {
        save(0);db().update("UPDATE user_profile_entity_fact SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE user_id=?",fixture.user);
        assertTrue(store.read(fixture.user).isEmpty());store.cleanupExpired();
        assertEquals(0L,db().queryForObject("SELECT count(*) FROM user_profile_entity_fact WHERE user_id=?",Long.class,fixture.user));
    }
    @Test void cleanupPreservesLiveFacts() {save(0);store.cleanupExpired();assertFalse(store.read(fixture.user).isEmpty());}
    @Test void modelResultAfterErasureCannotRecreateFacts() {
        long generation=store.capture(fixture.user);fixture.pauseAndErase();
        db().update("DELETE FROM user_profile_entity_fact WHERE user_id=?",fixture.user);
        assertThrows(ProfileGenerationFence.Rejected.class,()->save(generation));assertTrue(store.read(fixture.user).isEmpty());
    }
    @Test void erasureSerializesAfterAcceptedEntityWrite() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<?> writer=pool.submit(()->fixture.fence.write(fixture.user,0,()->{
                save(0);entered.countDown();
                try {assertTrue(release.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                return null;
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<?> eraser=pool.submit(()->{fixture.pauseAndErase();db().update("DELETE FROM user_profile_entity_fact WHERE user_id=?",fixture.user);});
            release.countDown();writer.get(5,TimeUnit.SECONDS);eraser.get(5,TimeUnit.SECONDS);
            assertTrue(store.read(fixture.user).isEmpty());assertThrows(ProfileGenerationFence.Rejected.class,()->save(0));
        } finally {release.countDown();}
    }
}
