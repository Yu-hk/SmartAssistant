package com.example.smartassistant.router.service.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG + Redis; only explicit dedicated fixtures, never a production database. */
@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
class ProfileProjectionReaderIntegrationTest {
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    final long user=System.currentTimeMillis()+ThreadLocalRandom.current().nextLong(1_000_000_000L);
    final String request="projection-it-"+UUID.randomUUID();
    List<String> keys;
    ProfileProjectionReader reader;
    @BeforeAll static void connect() {
        assertEquals("true",System.getenv("RUN_REDIS_INTEGRATION_TESTS"));
        String url=Objects.requireNonNull(System.getenv("PG_TEST_URL"));
        ds=new DriverManagerDataSource(url,Objects.requireNonNull(System.getenv("PG_TEST_USER")),Objects.requireNonNull(System.getenv("PG_TEST_PASSWORD")));
        jdbc=new JdbcTemplate(ds);
        assertTrue(List.of("smartassistant_integration","smartassistant_profile_integration").contains(jdbc.queryForObject("SELECT current_database()",String.class)));
        jdbc.execute("CREATE TABLE IF NOT EXISTS users(id bigint PRIMARY KEY)");
        Path docs=Path.of("docs/database/migrations"); if(!java.nio.file.Files.isDirectory(docs)) docs=Path.of("../docs/database/migrations");
        new ResourceDatabasePopulator(new FileSystemResource(docs.resolve("20260902_add_ecommerce_user_profiles.sql")),
                new FileSystemResource(docs.resolve("20260918_add_profile_lifecycle.sql")),
                new FileSystemResource(docs.resolve("20260919_add_profile_request_admission.sql"))).execute(ds);
        connection=new LettuceConnectionFactory(System.getenv().getOrDefault("TEST_REDIS_HOST","127.0.0.1"),Integer.parseInt(Objects.requireNonNull(System.getenv("TEST_REDIS_PORT"))));
        connection.afterPropertiesSet(); connection.start(); redis=new StringRedisTemplate(connection);
        assertEquals("PONG",redis.execute((org.springframework.data.redis.core.RedisCallback<String>)c->c.ping()));
    }
    @AfterAll static void close() { if(connection!=null) connection.destroy(); }
    @BeforeEach void setup() {
        jdbc.update("INSERT INTO users(id) VALUES (?)",user);
        jdbc.update("INSERT INTO profile_lifecycle(user_id) VALUES (?)",user);
        jdbc.update("INSERT INTO profile_request_admission(user_id,request_hash,input_hash,generation) VALUES (?,?,?,0)",user,ProfileProjectionReader.digest(request),ProfileProjectionReader.digest("fixture"));
        keys=List.of("routing:user-profile-context:"+request,"routing:user-profile-owner:"+request,"routing:user-profile-lifecycle:"+user);
        redis.opsForValue().set(keys.get(0),"READY\nprivate fixture");
        redis.opsForValue().set(keys.get(1),user+"|0"); redis.opsForValue().set(keys.get(2),"0|ACTIVE");
        reader=new ProfileProjectionReader(jdbc,redis,new DataSourceTransactionManager(ds));
    }
    @AfterEach void cleanup() { if(keys!=null) redis.delete(keys); jdbc.update("DELETE FROM users WHERE id=?",user); }
    @Test void activeAdmittedOwnedProjectionIsReadable() { assertEquals("READY\nprivate fixture",reader.read(user,request)); }
    @Test void legacyCacheIsOnlyAvailableInTheOriginalActiveGeneration() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertEquals("value",reader.baselineCache(user,()->{calls.incrementAndGet();return "value";}));
        jdbc.update("UPDATE profile_lifecycle SET generation=1,analysis_enabled=false WHERE user_id=?",user);
        assertNull(reader.baselineCache(user,()->{calls.incrementAndGet();return "unsafe";}));
        jdbc.update("UPDATE profile_lifecycle SET generation=2,analysis_enabled=true WHERE user_id=?",user);
        assertNull(reader.baselineCache(user,()->{calls.incrementAndGet();return "unsafe";}));
        assertEquals(1,calls.get());
    }
    @Test void independentControlFailureNeverInvokesLegacyCacheCallback() {
        var guard=org.mockito.Mockito.mock(com.example.smartassistant.common.memory.ProfileRecoveryGuard.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("unavailable")).when(guard).requireSafe();
        org.springframework.test.util.ReflectionTestUtils.setField(reader,"recoveryGuard",guard);
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(IllegalStateException.class,()->reader.baselineCache(user,()->{called.set(true);return "unsafe";}));
        assertFalse(called.get());
    }
    @Test void missingAdmissionRejectsEvenWithRestoredRedis() {
        jdbc.update("DELETE FROM profile_request_admission WHERE user_id=?",user);
        assertNull(reader.read(user,request));
    }
    @Test void foreignOwnerCannotRead() {
        redis.opsForValue().set(keys.get(1),(user+1)+"|0"); assertNull(reader.read(user,request));
    }
    @Test void pgPauseSuppressesBeforeRedisCleanup() {
        jdbc.update("UPDATE profile_lifecycle SET analysis_enabled=false,generation=generation+1 WHERE user_id=?",user);
        assertNull(reader.read(user,request)); assertTrue(redis.hasKey(keys.get(0)));
    }
    @Test void resumedGenerationCannotReadOldAdmission() {
        jdbc.update("UPDATE profile_lifecycle SET generation=2 WHERE user_id=?",user);
        redis.opsForValue().set(keys.get(1),user+"|2"); redis.opsForValue().set(keys.get(2),"2|ACTIVE");
        assertNull(reader.read(user,request));
    }
    @Test void pausedRedisBarrierRejects() { redis.opsForValue().set(keys.get(2),"0|PAUSED"); assertNull(reader.read(user,request)); }
    @Test void missingBarrierAfterRedisRestartRejects() { redis.delete(keys.get(2)); assertNull(reader.read(user,request)); }
    @Test void wrongRedisTypeFailsClosed() {
        redis.delete(keys.get(1)); redis.opsForList().rightPush(keys.get(1),"fixture"); assertNull(reader.read(user,request));
    }
    @Test void concurrentPauseWaitsUntilAcceptedReadCompletes() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var slow=new StringRedisTemplate(connection) {
            @Override public <T> T execute(RedisScript<T> script,List<String> k,Object... args) {
                entered.countDown(); await(release); return super.execute(script,k,args);
            }
        };
        var tested=new ProfileProjectionReader(jdbc,slow,new DataSourceTransactionManager(ds));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reading=pool.submit(()->tested.read(user,request));
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                var pausing=pool.submit(()->jdbc.update("UPDATE profile_lifecycle SET analysis_enabled=false WHERE user_id=?",user));
                assertThrows(TimeoutException.class,()->pausing.get(100,TimeUnit.MILLISECONDS));
                release.countDown(); assertNotNull(reading.get(3,TimeUnit.SECONDS)); pausing.get(3,TimeUnit.SECONDS);
                assertNull(reader.read(user,request));
            } finally { release.countDown(); }
        }
    }
    @Test void blockedPgRowDoesNotWaitIndefinitely() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var holder=pool.submit(()->new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(s->{
                jdbc.queryForMap("SELECT user_id FROM profile_lifecycle WHERE user_id=? FOR UPDATE",user); entered.countDown(); await(release);
            }));
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                assertThrows(org.springframework.dao.DataAccessException.class,()->reader.read(user,request));
            } finally { release.countDown(); holder.get(3,TimeUnit.SECONDS); }
        }
    }
    static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
