package com.example.smartassistant.consumer.service.session;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerSessionContextServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void followUp_shouldReuseOnlySameUserSessionOrder() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("customer-session:order:1:session-a")).thenReturn("ORD-LOAD000001003");
        CustomerSessionContextService service = new CustomerSessionContextService(redis);

        String enriched = service.enrichOrderReference("1", "session-a", "那它的快递单号是什么？");

        assertEquals("那它的快递单号是什么？（当前会话订单号：ORD-LOAD000001003）", enriched);
        verify(redis).expire(eq("customer-session:order:1:session-a"), any(Duration.class));
        verify(values, never()).get("customer-session:order:2:session-a");
    }

    @SuppressWarnings("unchecked")
    @Test
    void explicitOrder_shouldBeStoredInAuthenticatedScope() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        CustomerSessionContextService service = new CustomerSessionContextService(redis);

        String message = "查询订单 ORD-LOAD000001003 的状态";
        assertEquals(message, service.enrichOrderReference("1", "session-a", message));
        verify(values).set(eq("customer-session:order:1:session-a"),
                eq("ORD-LOAD000001003"), any(Duration.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void selectedCandidateOrder_shouldCarryCompletePreviousTurn() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("customer-session:order-candidates:1:session-a"))
                .thenReturn("ORD-LOAD000001001,ORD-LOAD000001002,ORD-LOAD000001003");
        when(values.get("customer-session:last-user-message:1:session-a"))
                .thenReturn("查询我的订单物流进度");
        when(values.get("customer-session:last-assistant-message:1:session-a"))
                .thenReturn("查到您最近的3笔订单，请选择需要查看的一笔。");
        CustomerSessionContextService service = new CustomerSessionContextService(redis);

        String enriched = service.enrichOrderReference(
                "1", "session-a", "ORD-LOAD000001001");

        assertEquals("【当前问题】\nORD-LOAD000001001"
                        + "\n【历史对话】\n用户：查询我的订单物流进度"
                        + "\n助手：查到您最近的3笔订单，请选择需要查看的一笔。"
                        + "\n【处理要求】\n请结合上一轮用户的查询目标，继续处理本轮选择的订单。",
                enriched);
    }

    @SuppressWarnings("unchecked")
    @Test
    void independentOrderNumber_shouldNotBorrowUnrelatedConversation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("customer-session:order-candidates:1:session-a"))
                .thenReturn("ORD-LOAD000001002,ORD-LOAD000001003");
        CustomerSessionContextService service = new CustomerSessionContextService(redis);

        assertEquals("ORD-LOAD000001001", service.enrichOrderReference(
                "1", "session-a", "ORD-LOAD000001001"));
    }

    @Test
    void anonymousSession_shouldNeverPersistOrReuseOrder() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CustomerSessionContextService service = new CustomerSessionContextService(redis);
        String message = "查询订单 ORD-LOAD000001003 的状态";
        assertEquals(message, service.enrichOrderReference("anonymous", "session-a", message));
        verifyNoInteractions(redis);
    }

    @SuppressWarnings("unchecked")
    @Test
    void recentOrderCandidates_shouldResolveOrdinalSelectionWithinSameUserSession() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("customer-session:order-candidates:1:session-a"))
                .thenReturn("ORD-LOAD000001003,ORD-LOAD000001002,ORD-LOAD000001001");
        CustomerSessionContextService service = new CustomerSessionContextService(redis);

        String enriched = service.enrichOrderReference("1", "session-a", "查询第2笔的物流进度");

        assertEquals("查询第2笔的物流进度（用户选择的订单号：ORD-LOAD000001002）", enriched);
        verify(values).set(eq("customer-session:order:1:session-a"),
                eq("ORD-LOAD000001002"), any(Duration.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void recentOrderResponse_shouldRememberCandidatesWithoutSelectingAmbiguousOrder() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        CustomerSessionContextService service = new CustomerSessionContextService(redis);

        service.rememberOrderCandidates("1", "session-a",
                "1. ORD-LOAD000001003\n2. ORD-LOAD000001002\n请选择订单");

        verify(values).set(eq("customer-session:order-candidates:1:session-a"),
                eq("ORD-LOAD000001003,ORD-LOAD000001002"), any(Duration.class));
        verify(redis).delete("customer-session:order:1:session-a");
    }

    @SuppressWarnings("unchecked")
    @Test
    void completedTurn_shouldBeStoredInUserScopedRouterHistory() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForList()).thenReturn(lists);
        CustomerSessionContextService service = new CustomerSessionContextService(redis);

        service.rememberConversationTurn(
                "1", "session-a", "查询我的订单物流进度", "查到您最近的3笔订单");

        verify(values).set(eq("customer-session:last-user-message:1:session-a"),
                eq("查询我的订单物流进度"), any(Duration.class));
        verify(values).set(eq("customer-session:last-assistant-message:1:session-a"),
                eq("查到您最近的3笔订单"), any(Duration.class));
        verify(lists).rightPush("chat:history:1:session-a", "用户：查询我的订单物流进度");
        verify(lists).rightPush("chat:history:1:session-a", "助手：查到您最近的3笔订单");
        verify(lists).trim("chat:history:1:session-a", -10, -1);
        verify(redis).expire(eq("chat:history:1:session-a"), any(Duration.class));
    }
}
