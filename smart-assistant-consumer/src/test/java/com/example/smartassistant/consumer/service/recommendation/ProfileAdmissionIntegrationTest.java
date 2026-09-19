package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
class ProfileAdmissionIntegrationTest {
    final ProfileGenerationFenceIntegrationTest fixture=new ProfileGenerationFenceIntegrationTest();
    ProfileAdmissionStore store;
    @BeforeAll static void database() {
        ProfileGenerationFenceIntegrationTest.database();
        Path docs=Path.of("docs/database/migrations");
        if(!java.nio.file.Files.isDirectory(docs)) docs=Path.of("../docs/database/migrations");
        new ResourceDatabasePopulator(new FileSystemResource(docs.resolve("20260919_add_profile_request_admission.sql")))
                .execute(ProfileGenerationFenceIntegrationTest.ds);
    }
    @BeforeEach void setup() { fixture.fixture(); store=new ProfileAdmissionStore(ProfileGenerationFenceIntegrationTest.jdbc,fixture.fence); }
    @AfterEach void cleanup() { fixture.cleanup(); }
    @Test void admissionIsIdempotentAndContainsOnlyDigests() {
        assertEquals(0L,store.admit(fixture.user,"request","private question"));
        assertEquals(0L,store.admit(fixture.user,"request","private question"));
        var rows=ProfileGenerationFenceIntegrationTest.jdbc.queryForList("SELECT * FROM profile_request_admission WHERE user_id=?",fixture.user);
        assertEquals(1,rows.size());
        assertEquals(64,rows.getFirst().get("request_hash").toString().length());
        assertEquals(64,rows.getFirst().get("input_hash").toString().length());
        assertFalse(rows.toString().contains("private question"));
    }
    @Test void reusedIdentityWithDifferentInputIsRejected() {
        store.admit(fixture.user,"request","original");
        assertThrows(ProfileGenerationFence.Rejected.class,()->store.admit(fixture.user,"request","changed"));
    }
    @Test void pausedAccountCannotAdmitAnyRequest() {
        fixture.pauseAndErase();
        assertThrows(ProfileGenerationFence.Rejected.class,()->store.admit(fixture.user,"new","question"));
        assertEquals(0L,count());
    }
    @Test void oldReceiptCannotBeRebasedAfterResume() {
        store.admit(fixture.user,"old","question"); fixture.pauseAndErase(); resume();
        assertThrows(ProfileGenerationFence.Rejected.class,()->store.admit(fixture.user,"old","question"));
        assertEquals(1L,store.admit(fixture.user,"new","question"));
        assertEquals(2L,count());
    }
    @Test void receiptSurvivesContentErasure() {
        store.admit(fixture.user,"old","question"); fixture.save(0); fixture.pauseAndErase();
        assertEquals(1L,count()); assertTrue(fixture.store.load(fixture.user).isEmpty());
    }
    @Test void accountsNeverShareAdmissionReceipts() {
        var second=new ProfileGenerationFenceIntegrationTest(); second.fixture();
        try {
            store.admit(fixture.user,"same","question"); fixture.pauseAndErase(); resume();
            assertEquals(0L,store.admit(second.user,"same","different"));
            assertThrows(ProfileGenerationFence.Rejected.class,()->store.admit(fixture.user,"same","question"));
        } finally { second.cleanup(); }
    }
    @Test void concurrentDuplicateAdmissionCreatesOneReceipt() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->store.admit(fixture.user,"same","question"));
            var two=pool.submit(()->store.admit(fixture.user,"same","question"));
            assertEquals(0L,one.get(3,TimeUnit.SECONDS)); assertEquals(0L,two.get(3,TimeUnit.SECONDS));
        }
        assertEquals(1L,count());
    }
    @Test void failedReceiptInsertRollsBackLifecycleCreation() {
        var failing=new org.springframework.jdbc.core.JdbcTemplate(ProfileGenerationFenceIntegrationTest.ds) {
            @Override public int update(String sql,Object... args) {
                if(sql.contains("INSERT INTO profile_request_admission")) throw new IllegalStateException("fixture");
                return super.update(sql,args);
            }
        };
        assertThrows(IllegalStateException.class,()->new ProfileAdmissionStore(failing,fixture.fence).admit(fixture.user,"new","question"));
        assertEquals(0L,count());
        assertEquals(0L,ProfileGenerationFenceIntegrationTest.jdbc.queryForObject("SELECT count(*) FROM profile_lifecycle WHERE user_id=?",Long.class,fixture.user));
    }
    private long count() { return ProfileGenerationFenceIntegrationTest.jdbc.queryForObject("SELECT count(*) FROM profile_request_admission WHERE user_id=?",Long.class,fixture.user); }
    private void resume() { ProfileGenerationFenceIntegrationTest.jdbc.update("UPDATE profile_lifecycle SET analysis_enabled=true WHERE user_id=?",fixture.user); }
}
