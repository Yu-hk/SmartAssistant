package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
class ProfileRedisPublicationIntegrationTest {
    final ProfileGenerationFenceIntegrationTest fixture=new ProfileGenerationFenceIntegrationTest();
    final String request="publication-it-"+UUID.randomUUID();
    @BeforeAll static void connect() { ProfileGenerationFenceIntegrationTest.database(); ProfileRequestRedisIntegrationTest.connect(); }
    @AfterAll static void close() { ProfileRequestRedisIntegrationTest.disconnect(); }
    @BeforeEach void setup() { fixture.fixture(); }
    @AfterEach void cleanup() {
        ProfileRequestRedisIntegrationTest.redis.delete(ProfileRequestRedisStore.keys(fixture.user,request)); fixture.cleanup();
    }
    void publish(StringRedisTemplate redis,long generation) {
        new ProfileRequestRedisStore(redis,fixture.fence).publish(fixture.user,request,generation,"READY\nfixture",null,true,Duration.ofSeconds(30));
    }
    @Test void activeGenerationCreatesPersistentBarrier() {
        publish(ProfileRequestRedisIntegrationTest.redis,0);
        assertEquals("0|ACTIVE",ProfileRequestRedisIntegrationTest.redis.opsForValue().get(ProfileRequestRedisStore.barrierKey(fixture.user)));
    }
    @Test void pauseRejectsLatePublicationEvenWhenRedisHasNoBarrier() {
        fixture.pauseAndErase();
        assertThrows(ProfileGenerationFence.Rejected.class,()->publish(ProfileRequestRedisIntegrationTest.redis,0));
        assertFalse(ProfileRequestRedisIntegrationTest.redis.hasKey(ProfileRequestRedisStore.barrierKey(fixture.user)));
    }
    @Test void resumedGenerationCannotAcceptOldProducer() {
        fixture.pauseAndErase();
        ProfileGenerationFenceIntegrationTest.jdbc.update("UPDATE profile_lifecycle SET analysis_enabled=true,generation=2 WHERE user_id=?",fixture.user);
        assertThrows(ProfileGenerationFence.Rejected.class,()->publish(ProfileRequestRedisIntegrationTest.redis,0));
        publish(ProfileRequestRedisIntegrationTest.redis,2);
    }
    @Test void pauseCannotSlipBetweenPgCheckAndRedisPublication() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var slow=new StringRedisTemplate(ProfileRequestRedisIntegrationTest.connection) {
            @Override public <T> T execute(RedisScript<T> script,List<String> keys,Object... args) {
                entered.countDown(); ProfileGenerationFenceIntegrationTest.await(release); return super.execute(script,keys,args);
            }
        };
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writing=pool.submit(()->publish(slow,0));
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS)); var pausing=pool.submit(fixture::pauseAndErase);
                assertThrows(TimeoutException.class,()->pausing.get(100,TimeUnit.MILLISECONDS));
                release.countDown(); writing.get(3,TimeUnit.SECONDS); pausing.get(3,TimeUnit.SECONDS);
                assertThrows(ProfileGenerationFence.Rejected.class,()->publish(ProfileRequestRedisIntegrationTest.redis,0));
            } finally { release.countDown(); }
        }
    }
}
