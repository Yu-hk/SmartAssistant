package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.memory.GovernedAgentMemoryStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
class GovernedAgentMemoryIntegrationTest {
    final ProfileGenerationFenceIntegrationTest fixture=new ProfileGenerationFenceIntegrationTest();
    GovernedAgentMemoryStore store;
    @BeforeAll static void database() {
        ProfileAdmissionIntegrationTest.database();
        Path path=Path.of("docs/database/migrations/20260919_add_governed_agent_memory.sql");
        if(!java.nio.file.Files.exists(path)) path=Path.of("..").resolve(path);
        new ResourceDatabasePopulator(new FileSystemResource(path)).execute(ProfileGenerationFenceIntegrationTest.ds);
    }
    @BeforeEach void setup() {
        fixture.fixture();store=new GovernedAgentMemoryStore(db(),new DataSourceTransactionManager(ProfileGenerationFenceIntegrationTest.ds));
        admit("request");
    }
    @AfterEach void cleanup() { fixture.cleanup(); }
    JdbcTemplate db() { return ProfileGenerationFenceIntegrationTest.jdbc; }
    String user() {return Long.toString(fixture.user);}
    void admit(String request) { new ProfileAdmissionStore(db(),fixture.fence).admit(fixture.user,request,"喜欢简洁回答"); }
    void save(long generation) {store.save("order",user(),generation,Map.of("replyStyle","简洁"));}
    @Test void requestAdmittedGenerationRoundTripsAndUpdates() {
        assertEquals(0,store.admission("order",user(),"request","喜欢简洁回答"));save(0);
        assertTrue(store.load("order",user()).get("replyStyle").startsWith("简洁 || "));
        store.save("order",user(),0,Map.of("replyStyle","详细"));
        assertTrue(store.load("order",user()).get("replyStyle").startsWith("详细 || "));
    }
    @Test void missingOrRewrittenEvidenceCannotStartExtraction() {
        assertThrows(GovernedAgentMemoryStore.Rejected.class,()->store.admission("order",user(),"missing","喜欢简洁回答"));
        assertThrows(GovernedAgentMemoryStore.Rejected.class,()->store.admission("order",user(),"request","模型改写的要求"));
        assertThrows(GovernedAgentMemoryStore.Rejected.class,()->store.admission("order",user(),null,"喜欢简洁回答"));
    }
    @Test void pauseSuppressesReadsAndWrites() {
        save(0);fixture.pauseAndErase();
        assertTrue(store.load("order",user()).isEmpty());
        assertThrows(GovernedAgentMemoryStore.Rejected.class,()->save(0));
        assertThrows(GovernedAgentMemoryStore.Rejected.class,()->store.admission("order",user(),"request","喜欢简洁回答"));
    }
    @Test void resumeRejectsOldRequestsAndAcceptsOnlyNewGeneration() {
        save(0);fixture.pauseAndErase(); db().update("UPDATE profile_lifecycle SET analysis_enabled=true WHERE user_id=?",fixture.user);
        assertTrue(store.load("order",user()).isEmpty());
        assertThrows(GovernedAgentMemoryStore.Rejected.class,()->store.admission("order",user(),"request","喜欢简洁回答"));
        admit("new");assertEquals(1,store.admission("order",user(),"new","喜欢简洁回答"));save(1);
        assertFalse(store.load("order",user()).isEmpty());assertThrows(GovernedAgentMemoryStore.Rejected.class,()->save(0));
    }
    @Test void userAndAgentScopesAreIsolated() {
        save(0);assertTrue(store.load("product",user()).isEmpty());
        assertTrue(store.load("order",Long.toString(fixture.user+100000)).isEmpty());
        assertThrows(GovernedAgentMemoryStore.Rejected.class,()->store.admission("order",Long.toString(fixture.user+100000),"request","喜欢简洁回答"));
    }
    @Test void invalidBatchCannotPartiallyPersist() {
        assertThrows(GovernedAgentMemoryStore.Rejected.class,()->store.save("order",user(),0,Map.of("replyStyle","简洁","invalid-key","坏值")));
        assertThrows(GovernedAgentMemoryStore.Rejected.class,()->store.save("order",user(),0,Map.of("replyStyle","a\nb")));
        assertTrue(store.load("order",user()).isEmpty());
    }
    @Test void secondWriteFailureRollsBackWholeBatch() {
        var broken=new JdbcTemplate(ProfileGenerationFenceIntegrationTest.ds) {
            int writes;
            @Override public int update(String sql,Object... args) {if(++writes==2) throw new IllegalStateException("fixture");return super.update(sql,args);}
        };
        var tested=new GovernedAgentMemoryStore(broken,new DataSourceTransactionManager(ProfileGenerationFenceIntegrationTest.ds));
        assertThrows(IllegalStateException.class,()->tested.save("order",user(),0,Map.of("replyStyle","简洁","preferBrand","华为")));
        assertTrue(store.load("order",user()).isEmpty());
    }
    @Test void expiredFactsAreNotRead() {
        save(0);db().update("UPDATE profile_agent_memory SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE user_id=?",fixture.user);
        assertTrue(store.load("order",user()).isEmpty());
    }
    @Test void lateModelCannotRecreateErasedFacts() {
        long generation=store.admission("order",user(),"request","喜欢简洁回答");
        fixture.pauseAndErase();db().update("DELETE FROM profile_agent_memory WHERE user_id=?",fixture.user);
        assertThrows(GovernedAgentMemoryStore.Rejected.class,()->save(generation));
        assertEquals(0L,db().queryForObject("SELECT count(*) FROM profile_agent_memory WHERE user_id=?",Long.class,fixture.user));
    }
    @Test void erasureSerializesWithAcceptedWrite() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(()->fixture.fence.write(fixture.user,0,()->{save(0);entered.countDown();ProfileGenerationFenceIntegrationTest.await(release);return null;}));
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                var erase=pool.submit(()->{fixture.pauseAndErase();db().update("DELETE FROM profile_agent_memory WHERE user_id=?",fixture.user);});
                assertThrows(TimeoutException.class,()->erase.get(100,TimeUnit.MILLISECONDS));
                release.countDown();writer.get(3,TimeUnit.SECONDS);erase.get(3,TimeUnit.SECONDS);
                assertTrue(store.load("order",user()).isEmpty());assertThrows(GovernedAgentMemoryStore.Rejected.class,()->save(0));
            } finally {release.countDown();}
        }
    }
}
