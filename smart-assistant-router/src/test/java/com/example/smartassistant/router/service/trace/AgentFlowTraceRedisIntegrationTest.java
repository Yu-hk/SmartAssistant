package com.example.smartassistant.router.service.trace;

import com.example.smartassistant.router.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_REDIS_INTEGRATION_TESTS", matches="true")
class AgentFlowTraceRedisIntegrationTest {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    final String id="trace-fixture-"+UUID.randomUUID();
    final String key="routing:execution-graph:"+id;
    final ObjectMapper mapper=new ObjectMapper();
    AgentFlowTraceStore store;
    @BeforeAll static void connect() {
        assertEquals("true",System.getenv("RUN_REDIS_INTEGRATION_TESTS"));
        connection=new LettuceConnectionFactory(System.getenv().getOrDefault("TEST_REDIS_HOST","127.0.0.1"),
                Integer.parseInt(Objects.requireNonNull(System.getenv("TEST_REDIS_PORT"))));
        connection.afterPropertiesSet();connection.start();redis=new StringRedisTemplate(connection);
        assertEquals("PONG",redis.execute((org.springframework.data.redis.core.RedisCallback<String>)c->c.ping()));
    }
    @AfterAll static void close(){if(connection!=null)connection.destroy();}
    @BeforeEach void setup(){store=new AgentFlowTraceStore(mapper,redis);}
    @AfterEach void cleanup(){redis.delete(key);}
    void start(){store.start(id,"private-question",null,new IntentGraph("private-question",List.of(
            new IntentGraph.IntentNode("n","private-description","product",List.of()))));}
    @Test void persistedTraceContainsMetadataOnlyWithBoundedExpiry() {
        start();store.complete(id,List.of(new SubTaskResult("n","task","product","private-profile-result",true)),List.of("product"),12);
        String raw=redis.opsForValue().get(key);
        assertNotNull(raw);assertFalse(raw.contains("private-"));
        long ttl=redis.getExpire(key,TimeUnit.MILLISECONDS);
        assertTrue(ttl>0 && ttl<=TimeUnit.HOURS.toMillis(24));
        assertEquals("completed",store.get(id).orElseThrow().status());
    }
    @Test void restoredLegacyTraceIsRedactedOnReadWithoutExtendingItsExpiry() throws Exception {
        var old=new AgentFlowSnapshot(id,"private-question","model","light",12,"completed",1,2L,
                List.of(new AgentFlowSnapshot.Node("n","private-description","product","agent","completed","private-profile",List.of(),1L)),List.of());
        redis.opsForValue().set(key,mapper.writeValueAsString(old),60,TimeUnit.SECONDS);
        long before=redis.getExpire(key,TimeUnit.MILLISECONDS);
        assertFalse(mapper.writeValueAsString(store.get(id).orElseThrow()).contains("private-"));
        assertTrue(redis.getExpire(key,TimeUnit.MILLISECONDS)<=before);
        // Read minimization is not physical deletion: the legacy bytes remain until expiry/approved cleanup.
        assertTrue(redis.opsForValue().get(key).contains("private-profile"));
    }
    @Test void redisDeletionWinsOverProcessFallback(){start();redis.delete(key);assertTrue(store.get(id).isEmpty());}
}
