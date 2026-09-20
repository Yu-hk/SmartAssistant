package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.memory.ProfileRecoveryGuard;
import com.example.smartassistant.consumer.entity.RoutingCallLog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same transaction + actual PG/Redis/POSIX journal, only in disposable test DB. */
@Tag("integration")
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
class ProfileControlArchiveIntegrationTest {
    final ProfileGenerationFenceIntegrationTest fixture=new ProfileGenerationFenceIntegrationTest();
    final ProfileControlJournalTest disk=new ProfileControlJournalTest();
    ProfileControlArchive archive;ProfileCleanupService service;
    static JdbcTemplate db(){return ProfileGenerationFenceIntegrationTest.jdbc;}
    static DataSourceTransactionManager manager(){return new DataSourceTransactionManager(ProfileGenerationFenceIntegrationTest.ds);}
    @BeforeAll static void database() {
        ProfileCleanupIntegrationTest.database();
        Path docs=Path.of("docs/database/migrations");if(!Files.isDirectory(docs)) docs=Path.of("../docs/database/migrations");
        var migration=new ResourceDatabasePopulator(new FileSystemResource(docs.resolve("20260919_add_profile_control_archive.sql")));
        migration.setSeparator(org.springframework.jdbc.datasource.init.ScriptUtils.EOF_STATEMENT_SEPARATOR);
        migration.execute(ProfileGenerationFenceIntegrationTest.ds);
        db().execute("CREATE TABLE IF NOT EXISTS routing_call_log(id bigserial PRIMARY KEY,user_id bigint,session_id text,user_input text,response_summary text,llm_received_question text)");
    }
    @AfterAll static void close(){ProfileRequestRedisIntegrationTest.disconnect();}
    @BeforeEach void setup() throws Exception {
        fixture.fixture();disk.setup();
        disk.source=db().queryForObject("SELECT source_id FROM profile_control_source WHERE singleton",UUID.class);
        disk.write("source.pin","1|"+disk.source+"\n");
        archive=new ProfileControlArchive(db(),manager(),true,disk.root.toString(),disk.source.toString());
        archive.sync();
        service=coordinator(db(),archive);
    }
    @AfterEach void cleanup() throws Exception {
        db().update("DELETE FROM routing_call_log WHERE user_id=?",fixture.user);
        ProfileRequestRedisIntegrationTest.redis.delete(ProfileRequestRedisStore.barrierKey(fixture.user));
        fixture.cleanup();disk.cleanup();
    }
    ProfileCleanupService coordinator(JdbcTemplate jdbc,ProfileControlArchive control) {
        var result=new ProfileCleanupService(jdbc,manager(),new ProfileRedisCleanup(ProfileRequestRedisIntegrationTest.redis),true);
        ReflectionTestUtils.setField(result,"archive",control);ReflectionTestUtils.setField(result,"derivedEnabled",true);
        ReflectionTestUtils.setField(result,"derived",new ProfileDerivedCleanup(jdbc,ProfileRequestRedisIntegrationTest.redis,new com.fasterxml.jackson.databind.ObjectMapper()));
        return result;
    }
    String receipt(UUID job,String target){return db().queryForObject("SELECT state FROM profile_cleanup_receipt WHERE job_id=? AND target=?",String.class,job,target);}
    void addLog(String prompt) {
        db().update("INSERT INTO routing_call_log(user_id,user_input,response_summary,llm_received_question) VALUES (?,'original question','original reply',?)",fixture.user,prompt);
    }
    @Test void pauseEventIsAtomicAndReceiptsRequireIndependentDurability() {
        fixture.save(0);addLog("profile-derived prompt");UUID key=UUID.randomUUID();
        UUID job=service.request(fixture.user,key);assertEquals(job,service.request(fixture.user,key));
        assertEquals(1L,db().queryForObject("SELECT count(*) FROM profile_control_outbox WHERE user_id=?",Long.class,fixture.user));
        assertTrue(service.runNext());assertEquals("SUCCEEDED",receipt(job,"BACKUP_RESTORE"));
        assertEquals("SUCCEEDED",receipt(job,"DERIVED_COPIES"));assertEquals("BLOCKED",receipt(job,"LEGACY_STORAGE"));
        assertEquals("PARTIAL",service.status(fixture.user,job).get("state"));
        assertNull(db().queryForObject("SELECT llm_received_question FROM routing_call_log WHERE user_id=?",String.class,fixture.user));
        assertEquals("original question",db().queryForObject("SELECT user_input FROM routing_call_log WHERE user_id=?",String.class,fixture.user));
        assertEquals("original reply",db().queryForObject("SELECT response_summary FROM routing_call_log WHERE user_id=?",String.class,fixture.user));
    }
    @Test void outboxFailureRollsBackPauseJobAndSequence() {
        fixture.save(0);long before=db().queryForObject("SELECT last_sequence FROM profile_control_source",Long.class);
        var failing=new JdbcTemplate(ProfileGenerationFenceIntegrationTest.ds) {
            @Override public int update(String sql,Object... args) {
                if(sql.startsWith("INSERT INTO profile_control_outbox")) throw new IllegalStateException("fixture outbox failed");
                return super.update(sql,args);
            }
        };
        var control=new ProfileControlArchive(failing,manager(),true,disk.root.toString(),disk.source.toString());
        assertThrows(IllegalStateException.class,()->coordinator(db(),control).request(fixture.user,UUID.randomUUID()));
        assertEquals(before,db().queryForObject("SELECT last_sequence FROM profile_control_source",Long.class));
        assertEquals(0L,fixture.fence.capture(fixture.user));assertTrue(fixture.store.load(fixture.user).isPresent());
        assertEquals(0L,db().queryForObject("SELECT count(*) FROM profile_cleanup_job WHERE user_id=?",Long.class,fixture.user));
    }
    @Test void missingIndependentPinNeverAcknowledgesBackupAndRetryWorks() throws Exception {
        UUID job=service.request(fixture.user,UUID.randomUUID());Files.delete(disk.root.resolve("source.pin"));
        assertTrue(service.runNext());assertEquals("RETRY",receipt(job,"BACKUP_RESTORE"));
        assertEquals("PARTIAL",service.status(fixture.user,job).get("state"));
        disk.write("source.pin","1|"+disk.source+"\n");
        db().update("UPDATE profile_cleanup_receipt SET next_attempt_at=CURRENT_TIMESTAMP WHERE job_id=?",job);
        assertTrue(service.runNext());assertEquals("SUCCEEDED",receipt(job,"BACKUP_RESTORE"));
    }
    @Test void restoredOlderDatabaseCannotExposeTheOldProfileOrOverwriteJournal() throws Exception {
        fixture.save(0);UUID job=service.request(fixture.user,UUID.randomUUID());archive.sync();
        var event=db().queryForMap("SELECT * FROM profile_control_outbox WHERE event_id=?",job);
        long sequence=((Number)event.get("sequence")).longValue();
        String before=Files.readString(disk.root.resolve("HEAD"));
        var guard=new ProfileRecoveryGuard(db(),true,disk.root,disk.source);
        ReflectionTestUtils.setField(fixture.fence,"recoveryGuard",guard);
        // Simulate rollback only inside the explicitly allowlisted, disposable fixture database.
        try {
            db().update("DELETE FROM profile_control_outbox WHERE event_id=?",job);
            db().update("UPDATE profile_control_source SET last_sequence=?",sequence-1);
            db().update("UPDATE profile_lifecycle SET generation=0,analysis_enabled=true WHERE user_id=?",fixture.user);
            assertThrows(ProfileRecoveryGuard.Unavailable.class,()->fixture.store.load(fixture.user));
            assertThrows(ProfileRecoveryGuard.Unavailable.class,()->fixture.fence.capture(fixture.user));
            assertThrows(ProfileRecoveryGuard.Unavailable.class,()->fixture.save(0));
            assertThrows(IllegalStateException.class,archive::sync);
            assertEquals(before,Files.readString(disk.root.resolve("HEAD")));
        } finally {
            db().update("INSERT INTO profile_control_outbox(sequence,event_id,source_id,user_id,generation,analysis_enabled) VALUES (?,?,?,?,?,?)",sequence,job,disk.source,fixture.user,1L,false);
            db().update("UPDATE profile_control_source SET last_sequence=?",sequence);
            db().update("UPDATE profile_lifecycle SET generation=1,analysis_enabled=false WHERE user_id=?",fixture.user);
        }
        assertDoesNotThrow(guard::requireSafe);assertTrue(fixture.store.load(fixture.user).isEmpty());
    }
    @Test void lateDiagnosticWriterCannotRecreatePromptButKeepsOriginalChat() {
        service.request(fixture.user,UUID.randomUUID());
        var row=new RoutingCallLog();row.setUserId(fixture.user);row.setLlmReceivedQuestion("late private profile prompt");
        new ProfileDiagnosticFence(db(),manager()).save(row,()->addLog(row.getLlmReceivedQuestion()));
        assertNull(db().queryForObject("SELECT llm_received_question FROM routing_call_log WHERE user_id=?",String.class,fixture.user));
        assertEquals("original question",db().queryForObject("SELECT user_input FROM routing_call_log WHERE user_id=?",String.class,fixture.user));
    }
    @Test void legacyDiagnosticExpiryNeverBecomesFalseSuccessOrDeletesSharedKey() {
        String session="profile-fixture-"+UUID.randomUUID(),key="routing:execution-graph:"+session;
        addLog("derived prompt");db().update("UPDATE routing_call_log SET session_id=? WHERE user_id=?",session,fixture.user);
        ProfileRequestRedisIntegrationTest.redis.opsForValue().set(key,"{\"question\":\"old private data\"}",java.time.Duration.ofSeconds(60));
        try {
            UUID job=service.request(fixture.user,UUID.randomUUID());service.runNext();
            assertEquals("SUCCEEDED",receipt(job,"POSTGRES_PROFILE"));assertEquals("RETRY",receipt(job,"DERIVED_COPIES"));
            assertTrue(ProfileRequestRedisIntegrationTest.redis.hasKey(key));
            assertNull(db().queryForObject("SELECT llm_received_question FROM routing_call_log WHERE user_id=?",String.class,fixture.user));
            ProfileRequestRedisIntegrationTest.redis.delete(key);
            db().update("UPDATE profile_cleanup_receipt SET next_attempt_at=CURRENT_TIMESTAMP WHERE job_id=?",job);service.runNext();
            assertEquals("SUCCEEDED",receipt(job,"DERIVED_COPIES"));
        } finally {ProfileRequestRedisIntegrationTest.redis.delete(key);}
    }
    @Test void fullyConfiguredDeletionCompletesAndDoubleClickCannotAdvanceAgain() {
        ReflectionTestUtils.setField(service,"legacy",new ProfileLegacyFiles(disk.root.resolve("legacy-users").toString(),true));
        assertTrue(service.operational());UUID job=service.request(fixture.user,UUID.randomUUID());
        assertEquals(job,service.request(fixture.user,UUID.randomUUID()));service.runNext();
        assertEquals("ONLINE_CLEANED",service.status(fixture.user,job).get("state"));
        assertEquals(job,service.request(fixture.user,UUID.randomUUID()));
        assertEquals(1L,db().queryForObject("SELECT generation FROM profile_lifecycle WHERE user_id=?",Long.class,fixture.user));
    }
    @Test void missingReceiptCannotBeReportedAsOnlineCleaned() {
        ReflectionTestUtils.setField(service,"legacy",new ProfileLegacyFiles(disk.root.resolve("legacy-users").toString(),true));
        UUID job=service.request(fixture.user,UUID.randomUUID());
        db().update("DELETE FROM profile_cleanup_receipt WHERE job_id=? AND target='LEGACY_STORAGE'",job);
        assertTrue(service.runNext());
        assertEquals(4L,db().queryForObject("SELECT count(*) FROM profile_cleanup_receipt WHERE job_id=? AND state='SUCCEEDED'",Long.class,job));
        assertEquals("PARTIAL",service.status(fixture.user,job).get("state"));
        db().update("INSERT INTO profile_cleanup_receipt(job_id,target,state) VALUES (?,'LEGACY_STORAGE','PENDING')",job);
        assertTrue(service.runNext());
        assertEquals("ONLINE_CLEANED",service.status(fixture.user,job).get("state"));
        db().update("DELETE FROM profile_cleanup_receipt WHERE job_id=? AND target='LEGACY_STORAGE'",job);
        assertEquals("PARTIAL",service.status(fixture.user,job).get("state"));
    }
    @Test void intactArchiveCannotAuthorizeMissingOrReenabledLifecycle() {
        service.request(fixture.user,UUID.randomUUID());archive.sync();
        var guard=new ProfileRecoveryGuard(db(),true,disk.root,disk.source);
        assertDoesNotThrow(guard::requireSafe);
        try {
            db().update("UPDATE profile_lifecycle SET analysis_enabled=true WHERE user_id=?",fixture.user);
            assertThrows(ProfileRecoveryGuard.Unavailable.class,guard::requireSafe);
            db().update("DELETE FROM profile_lifecycle WHERE user_id=?",fixture.user);
            assertThrows(ProfileRecoveryGuard.Unavailable.class,guard::requireSafe);
        } finally {
            db().update("INSERT INTO profile_lifecycle(user_id,generation,analysis_enabled) VALUES (?,1,false) ON CONFLICT(user_id) DO UPDATE SET generation=1,analysis_enabled=false",fixture.user);
        }
        assertDoesNotThrow(guard::requireSafe);
    }
}
