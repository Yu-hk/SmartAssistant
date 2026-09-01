package com.example.smartassistant.consumer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.startsWith;

@ExtendWith(MockitoExtension.class)
class RouterClientRedisDecisionTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ListOperations<String, String> listOperations;
    @Mock private ValueOperations<String, String> valueOperations;

    private RouterClient routerClient;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(startsWith("routing:cancellation:"))).thenReturn(null);
        routerClient = new RouterClient(redisTemplate, new ObjectMapper(), 3000, 120000);
    }

    @Test
    void readsDecisionAfterNotificationWithoutBlockingRedisCommand() {
        String requestId = "req-1";
        String notifyKey = "a2a:route:full-decision:notify:" + requestId;
        String decisionKey = "a2a:route:full-decision:" + requestId;
        when(listOperations.leftPop(notifyKey)).thenReturn(requestId);
        when(valueOperations.get(decisionKey))
                .thenReturn("{\"agentName\":\"general\",\"confidence\":0.95,\"intentTag\":\"greeting\"}");

        Map<String, Object> decision = routerClient.waitForDecisionFromRedis(requestId, 1000);

        assertNotNull(decision);
        assertEquals("general", decision.get("agentName"));
        verify(redisTemplate).delete(decisionKey);
        verify(redisTemplate).delete(notifyKey);
    }

    @Test
    void readsAlreadyAvailableDecisionEvenWhenNotificationIsMissing() {
        String requestId = "req-2";
        String decisionKey = "a2a:route:full-decision:" + requestId;
        when(valueOperations.get(decisionKey))
                .thenReturn("{\"agentName\":\"product\",\"confidence\":0.88}");

        Map<String, Object> decision = routerClient.waitForDecisionFromRedis(requestId, 1000);

        assertNotNull(decision);
        assertEquals("product", decision.get("agentName"));
    }

    @Test
    void forwardsProgressBeforeReturningAvailableDecision() {
        String requestId = "req-progress";
        String decisionKey = "a2a:route:full-decision:" + requestId;
        when(valueOperations.get(decisionKey))
                .thenReturn("{\"agentName\":\"order\",\"confidence\":0.91}");
        AtomicInteger polls = new AtomicInteger();

        Map<String, Object> decision = routerClient.waitForDecisionFromRedis(
                requestId, 1000, polls::incrementAndGet);

        assertNotNull(decision);
        assertEquals("order", decision.get("agentName"));
        assertEquals(1, polls.get());
    }

    @Test
    void cancellationSignalWinsOverAStaleDecision() {
        String requestId = "req-cancelled";
        when(valueOperations.get("routing:cancellation:" + requestId)).thenReturn("42");

        Map<String, Object> decision = routerClient.waitForDecisionFromRedis(requestId, 1000);

        assertNotNull(decision);
        assertEquals(true, decision.get("cancelled"));
        assertEquals("CANCELLED", decision.get("workflowStatus"));
        verify(redisTemplate).delete("a2a:route:full-decision:" + requestId);
    }
}
