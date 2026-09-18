package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in disposable PostgreSQL only; missing configuration is a failure, never a skip. */
@Tag("integration")
@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
class ProfileGenerationFenceIntegrationTest {
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis());
    ProfileGenerationFence fence;
    UserProfileSnapshotStore store;
    long user;

    @BeforeAll static void database() {
        String url=System.getenv("PG_TEST_URL"), username=System.getenv("PG_TEST_USER"), password=System.getenv("PG_TEST_PASSWORD");
        assertNotNull(url,"Dedicated PG_TEST_URL required"); assertNotNull(username); assertNotNull(password);
        ds=new DriverManagerDataSource(url,username,password); ds.setDriverClassName("org.postgresql.Driver"); jdbc=new JdbcTemplate(ds);
        String database=jdbc.queryForObject("SELECT current_database()",String.class);
        assertTrue(List.of("smartassistant_integration","smartassistant_profile_integration").contains(database),"Refusing a non-test database");
        jdbc.execute("CREATE TABLE IF NOT EXISTS public.users (id bigint PRIMARY KEY)");
        Path docs=Path.of("docs/database/migrations");
        if (!java.nio.file.Files.isDirectory(docs)) docs=Path.of("../docs/database/migrations");
        new ResourceDatabasePopulator(new FileSystemResource(docs.resolve("20260902_add_ecommerce_user_profiles.sql")),
                new FileSystemResource(docs.resolve("20260918_add_profile_lifecycle.sql"))).execute(ds);
    }
    @BeforeEach void fixture() {
        user=IDS.incrementAndGet(); jdbc.update("INSERT INTO users(id) VALUES (?)",user);
        fence=new ProfileGenerationFence(jdbc,new DataSourceTransactionManager(ds));
        store=new UserProfileSnapshotStore(jdbc,new ObjectMapper(),fence);
    }
    @AfterEach void cleanup() { if (user>0) jdbc.update("DELETE FROM users WHERE id = ?",user); }
    void save(long generation) { store.save(user,"fixture",0,report(),null,List.of(),generation); }
    void pauseAndErase() {
        new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO profile_lifecycle(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING",user);
            jdbc.update("UPDATE profile_lifecycle SET generation=generation+1, analysis_enabled=false WHERE user_id=?",user);
            jdbc.update("DELETE FROM user_profile_change_log WHERE user_id=?",user);
            jdbc.update("DELETE FROM user_profile_snapshot WHERE user_id=?",user);
        });
    }
    @Test void normalGenerationZeroStillCommitsSnapshotAndLog() {
        assertEquals(0L,fence.capture(user)); save(0);
        assertTrue(store.load(user).isPresent());
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM user_profile_change_log WHERE user_id=?",Long.class,user));
    }
    @Test void erasedCandidateCannotRecreateSnapshotWhenVersionAndIdempotencyRowsAreGone() {
        save(0); pauseAndErase(); assertThrows(ProfileGenerationFence.Rejected.class,()->save(0));
        assertTrue(store.load(user).isEmpty());
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM user_profile_change_log WHERE user_id=?",Long.class,user));
    }
    @Test void reenabledAccountStillRejectsLegacyGenerationButAcceptsNewGeneration() {
        pauseAndErase(); jdbc.update("UPDATE profile_lifecycle SET analysis_enabled=true WHERE user_id=?",user);
        assertEquals(1L,fence.capture(user));
        assertThrows(ProfileGenerationFence.Rejected.class,()->save(0)); save(1);
        assertEquals(1L,jdbc.queryForObject("SELECT generation FROM user_profile_snapshot WHERE user_id=?",Long.class,user));
    }
    @Test void pausedOrWrongGenerationSnapshotIsNotReadable() {
        save(0); jdbc.update("UPDATE profile_lifecycle SET analysis_enabled=false WHERE user_id=?",user);
        assertTrue(store.load(user).isEmpty()); assertThrows(ProfileGenerationFence.Rejected.class,()->fence.capture(user));
        jdbc.update("UPDATE profile_lifecycle SET analysis_enabled=true, generation=1 WHERE user_id=?",user);
        assertTrue(store.load(user).isEmpty());
    }
    @Test void failedChangeLogRollsBackSnapshotAsWell() {
        JdbcTemplate failing=new JdbcTemplate(ds) {
            @Override public int update(String sql,Object... args) {
                if (sql.startsWith("INSERT INTO user_profile_change_log")) throw new IllegalStateException("fixture log failure");
                return super.update(sql,args);
            }
        };
        var tested=new UserProfileSnapshotStore(failing,new ObjectMapper(),fence);
        assertThrows(IllegalStateException.class,()->tested.save(user,"failed",0,report(),null,List.of(),0));
        assertTrue(store.load(user).isEmpty());
    }
    @Test void concurrentErasureWaitsForAcceptedCommitThenRemovesIt() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            Future<?> writer=pool.submit(()->fence.write(user,0,()-> {
                entered.countDown(); await(release); save(0); return null;
            }));
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                var erasing=new CountDownLatch(1);
                Future<?> eraser=pool.submit(()->{ erasing.countDown(); pauseAndErase(); });
                assertTrue(erasing.await(1,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->eraser.get(100,TimeUnit.MILLISECONDS));
                release.countDown(); writer.get(3,TimeUnit.SECONDS); eraser.get(3,TimeUnit.SECONDS);
                assertThrows(ProfileGenerationFence.Rejected.class,()->save(0)); assertTrue(store.load(user).isEmpty());
            } finally { release.countDown(); }
        }
    }
    @Test void lockWaitIsBoundedAndDoesNotPersist() throws Exception {
        fence.write(user,0,()->null);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var pool=Executors.newSingleThreadExecutor()) {
            Future<?> holder=pool.submit(()->new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(status->{
                jdbc.queryForMap("SELECT user_id FROM profile_lifecycle WHERE user_id=? FOR UPDATE",user); entered.countDown(); await(release);
            }));
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS)); long start=System.nanoTime();
                assertThrows(org.springframework.dao.DataAccessException.class,()->save(0));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2500);
            } finally { release.countDown(); holder.get(3,TimeUnit.SECONDS); }
        }
        assertTrue(store.load(user).isEmpty());
    }
    static LLMPreferenceExtractor.UserInsightReport report() {
        return new LLMPreferenceExtractor.UserInsightReport(
                new LLMPreferenceExtractor.ProfileUpdate("CREATE",List.of("commerceAssessment"),List.of(),"fixture",List.of()),
                java.util.Map.of(),List.of(),java.util.Map.of(),List.of(),List.of(),
                new LLMPreferenceExtractor.CommerceAssessment(true,"深度咨询","审慎型","中",75,"低",List.of(),"当前",false,List.of(),List.of()),List.of());
    }
    static void await(CountDownLatch latch) {
        try { if(!latch.await(2,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
