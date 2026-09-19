package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG + dedicated Redis only. No shared production user, queue or database flush. */
@Tag("integration")
@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
class ProfileCleanupIntegrationTest {
    final ProfileGenerationFenceIntegrationTest fixture=new ProfileGenerationFenceIntegrationTest();
    final ProfileRequestRedisIntegrationTest redisFixture=new ProfileRequestRedisIntegrationTest();
    ProfileCleanupService service;
    ProfileRedisCleanup cleaner;
    @BeforeAll static void database() {
        ProfileAdmissionIntegrationTest.database();
        Path docs=Path.of("docs/database/migrations");
        if(!java.nio.file.Files.isDirectory(docs)) docs=Path.of("../docs/database/migrations");
        for(String name:List.of("20260919_add_profile_commit_candidates.sql","20260919_add_profile_entity_facts.sql",
                "20260919_add_governed_agent_memory.sql","20260919_add_profile_cleanup_jobs.sql"))
            new ResourceDatabasePopulator(new FileSystemResource(docs.resolve(name))).execute(ProfileGenerationFenceIntegrationTest.ds);
        ProfileRequestRedisIntegrationTest.connect();
    }
    @AfterAll static void close() { ProfileRequestRedisIntegrationTest.disconnect(); }
    @BeforeEach void setup() {
        fixture.fixture(); cleaner=new ProfileRedisCleanup(ProfileRequestRedisIntegrationTest.redis);
        service=coordinator(db(),cleaner);
        redisFixture.keys(fixture.user,redisFixture.request);
        redisFixture.cleanup.add("user:profile:"+fixture.user);
        redisFixture.cleanup.add("user_profile:"+fixture.user);
    }
    @AfterEach void cleanup() { redisFixture.clean();fixture.cleanup(); }
    JdbcTemplate db() { return ProfileGenerationFenceIntegrationTest.jdbc; }
    ProfileCleanupService coordinator(JdbcTemplate jdbc,ProfileRedisCleanup adapter) {
        return new ProfileCleanupService(jdbc,new DataSourceTransactionManager(ProfileGenerationFenceIntegrationTest.ds),adapter,true);
    }
    UUID request() { return service.request(fixture.user,UUID.randomUUID()); }
    void publish() {
        new ProfileRequestRedisStore(ProfileRequestRedisIntegrationTest.redis,fixture.fence)
                .publish(fixture.user,redisFixture.request,0,"READY:private","candidate",true,Duration.ofSeconds(60));
    }
    String receipt(UUID job,String target) {
        return db().queryForObject("SELECT state FROM profile_cleanup_receipt WHERE job_id=? AND target=?",String.class,job,target);
    }
    long count(String table) { return db().queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Long.class,fixture.user); }
    void due(UUID job) { db().update("UPDATE profile_cleanup_receipt SET next_attempt_at=CURRENT_TIMESTAMP WHERE job_id=?",job); }

    @Test void requestIsIdempotentAndOwnershipRestricted() {
        UUID key=UUID.randomUUID(),job=service.request(fixture.user,key);
        assertEquals(job,service.request(fixture.user,key));
        assertEquals(1L,db().queryForObject("SELECT generation FROM profile_lifecycle WHERE user_id=?",Long.class,fixture.user));
        assertFalse(db().queryForObject("SELECT analysis_enabled FROM profile_lifecycle WHERE user_id=?",Boolean.class,fixture.user));
        assertEquals(5L,db().queryForObject("SELECT count(*) FROM profile_cleanup_receipt WHERE job_id=?",Long.class,job));
        assertTrue(service.status(fixture.user+1,job).isEmpty());
        assertEquals("PAUSED",service.status(fixture.user,job).get("state"));
        assertThrows(ProfileGenerationFence.Rejected.class,()->fixture.save(0));
    }
    @Test void successfulKnownTargetsRemainPartialUntilAllInventoriesAreVerified() {
        fixture.save(0);publish();
        new ProfileAdmissionStore(db(),fixture.fence).admit(fixture.user,"original","question");
        db().update("INSERT INTO user_profile_entity_fact(user_id,generation,category,fact_value) VALUES (?,0,'hobby','music')",fixture.user);
        db().update("INSERT INTO profile_agent_memory(user_id,agent,memory_key,memory_value,generation) VALUES (?,'order','style','concise',0)",fixture.user);
        db().update("INSERT INTO profile_commit_candidate(candidate_id,user_id,request_id,generation,payload,expires_at) VALUES (?,?,?,0,'{}',CURRENT_TIMESTAMP+INTERVAL '1 hour')",UUID.randomUUID().toString(),fixture.user,"candidate");
        ProfileRequestRedisIntegrationTest.redis.opsForHash().put("user:profile:"+fixture.user,"hobby","music");
        ProfileRequestRedisIntegrationTest.redis.opsForValue().set("user_profile:"+fixture.user,"private legacy answer profile");
        UUID job=request(); assertTrue(service.runNext());assertFalse(service.runNext());
        for(String table:List.of("user_profile_snapshot","user_profile_change_log","profile_agent_memory","user_profile_entity_fact","profile_commit_candidate")) assertEquals(0L,count(table),table);
        assertEquals(1L,count("profile_request_admission"));
        assertEquals("SUCCEEDED",receipt(job,"POSTGRES_PROFILE")); assertEquals("SUCCEEDED",receipt(job,"REDIS_INDEXED"));
        assertEquals("BLOCKED",receipt(job,"LEGACY_STORAGE")); assertEquals("BLOCKED",receipt(job,"DERIVED_COPIES")); assertEquals("BLOCKED",receipt(job,"BACKUP_RESTORE"));
        assertEquals("PARTIAL",service.status(fixture.user,job).get("state"));
        assertEquals("1|PAUSED",ProfileRequestRedisIntegrationTest.redis.opsForValue().get(ProfileRequestRedisStore.barrierKey(fixture.user)));
        for(String key:ProfileRequestRedisStore.keys(fixture.user,redisFixture.request).subList(0,5)) assertFalse(ProfileRequestRedisIntegrationTest.redis.hasKey(key));
        assertFalse(ProfileRequestRedisIntegrationTest.redis.hasKey("user:profile:"+fixture.user));
        assertFalse(ProfileRequestRedisIntegrationTest.redis.hasKey("user_profile:"+fixture.user));
        assertThrows(ProfileGenerationFence.Rejected.class,this::publish);
    }
    @Test void failedRedisTargetRetriesAfterServiceRestartWithoutRepeatingPgDeletion() {
        var failed=new ProfileRedisCleanup(ProfileRequestRedisIntegrationTest.redis) {
            @Override public void clean(Long user,long generation) { throw new IllegalStateException("private detail"); }
        };
        fixture.save(0);UUID job=request();coordinator(db(),failed).runNext();
        assertEquals("SUCCEEDED",receipt(job,"POSTGRES_PROFILE"));assertEquals("RETRY",receipt(job,"REDIS_INDEXED"));
        assertFalse(service.status(fixture.user,job).toString().contains("private detail"));
        assertFalse(service.runNext());due(job);assertTrue(coordinator(db(),cleaner).runNext());
        assertEquals("SUCCEEDED",receipt(job,"REDIS_INDEXED"));
        assertEquals(1,db().queryForObject("SELECT attempts FROM profile_cleanup_receipt WHERE job_id=? AND target='POSTGRES_PROFILE'",Integer.class,job));
        assertEquals(2,db().queryForObject("SELECT attempts FROM profile_cleanup_receipt WHERE job_id=? AND target='REDIS_INDEXED'",Integer.class,job));
    }
    @Test void receiptFailureRollsBackPgDeletion() {
        fixture.save(0);UUID job=request();
        var failing=new JdbcTemplate(ProfileGenerationFenceIntegrationTest.ds) {
            @Override public int update(String sql,Object... args) {
                if(sql.contains("state='SUCCEEDED'") && args.length==2 && "POSTGRES_PROFILE".equals(args[1])) throw new IllegalStateException("crash before receipt");
                return super.update(sql,args);
            }
        };
        coordinator(failing,cleaner).runNext();
        assertEquals(1L,count("user_profile_snapshot"));assertEquals(1L,count("user_profile_change_log"));
        assertEquals("RETRY",receipt(job,"POSTGRES_PROFILE"));due(job);service.runNext();assertEquals(0L,count("user_profile_snapshot"));
    }
    @Test void redisSuccessBeforeReceiptFailureIsSafelyRepeatable() {
        publish();UUID job=request();
        var failing=new JdbcTemplate(ProfileGenerationFenceIntegrationTest.ds) {
            @Override public int update(String sql,Object... args) {
                if(sql.contains("state='SUCCEEDED'") && args.length==2 && "REDIS_INDEXED".equals(args[1])) throw new IllegalStateException("receipt unavailable");
                return super.update(sql,args);
            }
        };
        coordinator(failing,cleaner).runNext();assertEquals("RETRY",receipt(job,"REDIS_INDEXED"));
        assertFalse(ProfileRequestRedisIntegrationTest.redis.hasKey(ProfileRequestRedisStore.keys(fixture.user,redisFixture.request).getFirst()));
        due(job);coordinator(db(),cleaner).runNext();assertEquals("SUCCEEDED",receipt(job,"REDIS_INDEXED"));
    }
    @Test void obsoleteJobCannotDeleteResumedGeneration() {
        UUID job=request();db().update("UPDATE profile_lifecycle SET generation=2,analysis_enabled=true WHERE user_id=?",fixture.user);
        fixture.save(2);service.runNext();
        assertEquals("STALE",service.status(fixture.user,job).get("state"));assertEquals(1L,count("user_profile_snapshot"));
        assertEquals("BLOCKED",receipt(job,"POSTGRES_PROFILE"));
        assertFalse(ProfileRequestRedisIntegrationTest.redis.hasKey(ProfileRequestRedisStore.barrierKey(fixture.user)));
    }
    @Test void repeatedDistinctJobDoesNotLetOldJobCleanNewGeneration() {
        UUID old=request(),current=request();service.runNext();
        assertEquals("STALE",service.status(fixture.user,old).get("state"));service.runNext();
        assertEquals("PARTIAL",service.status(fixture.user,current).get("state"));
        assertEquals("2|PAUSED",ProfileRequestRedisIntegrationTest.redis.opsForValue().get(ProfileRequestRedisStore.barrierKey(fixture.user)));
    }
    @Test void foreignIndexedOwnerIsPreservedAndReceiptRetries() {
        var keys=ProfileRequestRedisStore.keys(fixture.user,redisFixture.request);publish();
        ProfileRequestRedisIntegrationTest.redis.opsForValue().set(keys.get(3),(fixture.user+1)+"|0");
        UUID job=request();service.runNext();
        assertEquals("RETRY",receipt(job,"REDIS_INDEXED"));assertEquals("READY:private",ProfileRequestRedisIntegrationTest.redis.opsForValue().get(keys.getFirst()));
        assertEquals("1|PAUSED",ProfileRequestRedisIntegrationTest.redis.opsForValue().get(keys.get(5)));
    }
    @Test void unownedPayloadAndWrongTypesFailBeforeAnyPayloadDeletion() {
        var keys=ProfileRequestRedisStore.keys(fixture.user,redisFixture.request);publish();
        ProfileRequestRedisIntegrationTest.redis.delete(keys.get(3));
        assertThrows(IllegalStateException.class,()->cleaner.clean(fixture.user,1));
        assertTrue(ProfileRequestRedisIntegrationTest.redis.hasKey(keys.getFirst()));
        ProfileRequestRedisIntegrationTest.redis.delete(keys.get(4));ProfileRequestRedisIntegrationTest.redis.opsForList().rightPush(keys.get(4),"wrong type");
        assertThrows(IllegalStateException.class,()->cleaner.clean(fixture.user,1));
        assertTrue(ProfileRequestRedisIntegrationTest.redis.hasKey(keys.getFirst()));
    }
    @Test void cleanupIsIdempotentAndCannotRegressLargeGenerationBarrier() {
        cleaner.clean(fixture.user,9007199254740993L);cleaner.clean(fixture.user,9007199254740993L);
        assertThrows(IllegalStateException.class,()->cleaner.clean(fixture.user,9007199254740992L));
        assertEquals("9007199254740993|PAUSED",ProfileRequestRedisIntegrationTest.redis.opsForValue().get(ProfileRequestRedisStore.barrierKey(fixture.user)));
        assertEquals(-1L,ProfileRequestRedisIntegrationTest.redis.getExpire(ProfileRequestRedisStore.barrierKey(fixture.user)));
    }
    @Test void legacyAnswerProfileOwnershipAndUnexpectedTypesAreRespected() {
        var redis=ProfileRequestRedisIntegrationTest.redis;
        String owned="user_profile:"+fixture.user,foreign="user_profile:"+(fixture.user+1);
        redisFixture.cleanup.add(foreign);
        redis.opsForValue().set(foreign,"other user's private profile");
        redis.opsForList().rightPush(owned,"unexpected storage type");
        UUID job=request();service.runNext();
        assertEquals("RETRY",receipt(job,"REDIS_INDEXED"));
        assertEquals(1L,redis.opsForList().size(owned));
        assertEquals("other user's private profile",redis.opsForValue().get(foreign));
        redis.delete(owned);redis.opsForValue().set(owned,"owned legacy profile");
        due(job);service.runNext();
        assertEquals("SUCCEEDED",receipt(job,"REDIS_INDEXED"));
        assertFalse(redis.hasKey(owned));
        assertEquals("other user's private profile",redis.opsForValue().get(foreign));
    }
    @Test void anotherUsersDataIsUnaffected() {
        var other=new ProfileGenerationFenceIntegrationTest();other.fixture();
        try {
            other.save(0);fixture.save(0);request();service.runNext();
            assertTrue(other.store.load(other.user).isPresent());assertEquals(0L,other.fence.capture(other.user));
        } finally {other.cleanup();}
    }
    @Test void ownAnswerAndSummaryPrefixesAreCleanedWithoutMatchingAnotherUser() {
        var redis=ProfileRequestRedisIntegrationTest.redis;
        for(String pattern:java.util.List.of("answer:%s:q","user:memory:%s:1","consumer:semantic-answer:v4:u%s:q","router:product-node:v2:u%s:q")) {
            String own=pattern.formatted(fixture.user),other=pattern.formatted(fixture.user+1);
            redisFixture.cleanup.add(own);redisFixture.cleanup.add(other);
            redis.opsForValue().set(own,"owned synthetic");redis.opsForValue().set(other,"other synthetic");
        }
        UUID job=request();service.runNext();assertEquals("SUCCEEDED",receipt(job,"REDIS_INDEXED"));
        for(String pattern:java.util.List.of("answer:%s:q","user:memory:%s:1","consumer:semantic-answer:v4:u%s:q","router:product-node:v2:u%s:q")) {
            assertFalse(redis.hasKey(pattern.formatted(fixture.user)));
            assertEquals("other synthetic",redis.opsForValue().get(pattern.formatted(fixture.user+1)));
        }
    }
}
