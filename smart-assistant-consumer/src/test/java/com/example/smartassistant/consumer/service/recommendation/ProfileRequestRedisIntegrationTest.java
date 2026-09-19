package com.example.smartassistant.consumer.service.recommendation;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in dedicated Redis only. Deletes exact fixture keys, never a shared database. */
class ProfileRequestRedisIntegrationTest {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static ProfileRequestRedisStore store;
    final Long user=1_000_000_000L+ThreadLocalRandom.current().nextLong(1_000_000_000L);
    final String request="profile-it-"+UUID.randomUUID();
    final Set<String> cleanup=new HashSet<>();

    @BeforeAll static void connect() {
        Assumptions.assumeTrue("true".equals(System.getenv("RUN_REDIS_INTEGRATION_TESTS")));
        String port=System.getenv("TEST_REDIS_PORT");
        assertNotNull(port,"An explicit dedicated TEST_REDIS_PORT is required");
        connection=new LettuceConnectionFactory(System.getenv().getOrDefault("TEST_REDIS_HOST","127.0.0.1"),Integer.parseInt(port));
        connection.afterPropertiesSet(); connection.start();
        redis=new StringRedisTemplate(connection); store=new ProfileRequestRedisStore(redis);
        assertEquals("PONG",redis.execute((org.springframework.data.redis.core.RedisCallback<String>)c->c.ping()));
    }
    @AfterAll static void disconnect() { if(connection!=null) connection.destroy(); }
    @AfterEach void clean() { if(!cleanup.isEmpty()) redis.delete(cleanup); }
    List<String> keys(Long owner,String id) {
        var keys=ProfileRequestRedisStore.keys(owner,id); cleanup.addAll(keys); return keys;
    }
    void publish(Long owner,String id,long generation,String state,String candidate,boolean done,int seconds) {
        keys(owner,id); store.publish(owner,id,generation,state,candidate,done,Duration.ofSeconds(seconds));
    }
    @Test void publishesCandidateProjectionReceiptAndIndexTogether() {
        var k=keys(user,request);
        publish(user,request,4,"READY:fixture","fixture-candidate",true,30);
        assertEquals(List.of("READY:fixture","fixture-candidate","DONE",user+"|4"),redis.opsForValue().multiGet(k.subList(0,4)));
        assertNotNull(redis.opsForZSet().score(k.get(4),request));
        for(String key:k) assertTrue(redis.getExpire(key,TimeUnit.MILLISECONDS)>28000);
    }
    @Test void foreignOwnerCannotOverwriteOrAcquireIndex() {
        var k=keys(user,request); var foreign=keys(user+1,request);
        publish(user,request,0,"PENDING",null,false,30);
        assertThrows(IllegalStateException.class,()->publish(user+1,request,0,"READY:foreign","foreign",true,30));
        assertEquals("PENDING",redis.opsForValue().get(k.get(0)));
        assertFalse(redis.hasKey(foreign.get(4))); assertFalse(redis.hasKey(k.get(1)));
    }
    @Test void differentGenerationCannotOverwriteSameRequest() {
        var k=keys(user,request); publish(user,request,4,"PENDING",null,false,30);
        assertThrows(IllegalStateException.class,()->publish(user,request,3,"READY:old","old",true,30));
        assertThrows(IllegalStateException.class,()->publish(user,request,5,"READY:new","new",true,30));
        assertEquals(user+"|4",redis.opsForValue().get(k.get(3))); assertFalse(redis.hasKey(k.get(1)));
    }
    @Test void latePendingAndTerminalWritesDoNotOverwriteCompletedResult() {
        var k=keys(user,request); publish(user,request,0,"READY:first","first",true,30);
        publish(user,request,0,"PENDING",null,false,120);
        publish(user,request,0,"FAILED","late",true,120);
        assertEquals("READY:first",redis.opsForValue().get(k.get(0)));
        assertEquals("first",redis.opsForValue().get(k.get(1)));
        assertTrue(redis.getExpire(k.get(0))<=30);
    }
    @Test void wrongTypeIsRejectedBeforeAnyPayloadMutation() {
        var k=keys(user,request); redis.opsForList().rightPush(k.get(4),"fixture");
        assertThrows(IllegalStateException.class,()->publish(user,request,0,"READY:test","test",true,30));
        for(String key:k.subList(0,4)) assertFalse(redis.hasKey(key));
        assertEquals(List.of("fixture"),redis.opsForList().range(k.get(4),0,-1));
    }
    @Test void legacyUnownedProjectionIsNotAdopted() {
        var k=keys(user,request); redis.opsForValue().set(k.get(0),"legacy",Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class,()->publish(user,request,0,"PENDING",null,false,30));
        assertEquals("legacy",redis.opsForValue().get(k.get(0)));
        assertFalse(redis.hasKey(k.get(3))); assertFalse(redis.hasKey(k.get(4)));
    }
    @Test void retirementRequiresMatchingOwnerGenerationAndPayload() {
        var k=keys(user,request); publish(user,request,2,"READY:test","candidate",true,30);
        store.retire(user+1,request,2,"candidate"); store.retire(user,request,1,"candidate");
        store.retire(user,request,2,"different");
        assertEquals("candidate",redis.opsForValue().get(k.get(1)));
        store.retire(user,request,2,"candidate"); assertFalse(redis.hasKey(k.get(1)));
        for(String key:List.of(k.get(0),k.get(2),k.get(3),k.get(4))) assertTrue(redis.hasKey(key));
    }
    @Test void shorterRequestDoesNotShortenUserIndexLifetime() {
        var k=keys(user,request); publish(user,request,0,"PENDING",null,false,120);
        publish(user,request+"-short",0,"PENDING",null,false,30);
        assertTrue(redis.getExpire(k.get(4))>110);
        publish(user,request,0,"READY:test","candidate",true,30);
        assertTrue(redis.getExpire(k.get(3))>110);
        assertTrue(redis.getExpire(k.get(2))>110,"Terminal receipt must live as long as its owner fence");
    }
    @Test void activeIndexIsBoundedAndExpiredMetadataIsPruned() {
        var k=keys(user,request); long future=System.currentTimeMillis()+600000;
        for(int i=0;i<500;i++) redis.opsForZSet().add(k.get(4),"fixture-"+i,future);
        redis.expire(k.get(4),Duration.ofSeconds(600));
        assertThrows(IllegalStateException.class,()->publish(user,request,0,"PENDING",null,false,30));
        assertFalse(redis.hasKey(k.get(0))); assertFalse(redis.hasKey(k.get(3)));
        redis.opsForZSet().add(k.get(4),"fixture-0",1);
        publish(user,request,0,"PENDING",null,false,30);
        assertEquals(500L,redis.opsForZSet().zCard(k.get(4)));
        assertNull(redis.opsForZSet().score(k.get(4),"fixture-0"));
    }
    @Test void concurrentOwnersHaveExactlyOneWinner() throws Exception {
        keys(user,request); keys(user+1,request);
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var results=new ArrayList<Future<Boolean>>();
            for(long id:List.of(user,user+1)) results.add(pool.submit(()->{
                start.await();
                try { store.publish(id,request,0,"READY:"+id,"fixture",true,Duration.ofSeconds(30)); return true; }
                catch(IllegalStateException rejected) { return false; }
            }));
            start.countDown(); int winners=0;
            for(var result:results) if(result.get(5,TimeUnit.SECONDS)) winners++;
            assertEquals(1,winners);
        } finally { pool.shutdownNow(); }
    }
}
