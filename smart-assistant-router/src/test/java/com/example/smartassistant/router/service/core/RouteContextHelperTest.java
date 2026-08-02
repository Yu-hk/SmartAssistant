package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.RouteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class RouteContextHelperTest {

    @SuppressWarnings("unchecked")
    @Test
    void history_shouldBeLoadedFromAuthenticatedUserAndSessionScope() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(lists.size("chat:history:1:session-a")).thenReturn(2L);
        when(lists.range("chat:history:1:session-a", 0, 1))
                .thenReturn(List.of("用户：查询我的订单物流进度", "助手：查到最近3笔订单"));
        RouteContextHelper helper = new RouteContextHelper(redis, 10);

        Map<String, Object> context = helper.buildContext(
                new RouteRequest(1L, "ORD-LOAD000001001", "session-a", false, "request-2"));

        assertEquals(List.of("用户：查询我的订单物流进度", "助手：查到最近3笔订单"),
                context.get("conversationHistory"));
        verify(lists, never()).size("chat:history:session-a");
    }

    @Test
    void anonymousHistory_shouldNotBeLoaded() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RouteContextHelper helper = new RouteContextHelper(redis, 10);

        Map<String, Object> context = helper.buildContext(
                new RouteRequest(0L, "hello", "session-a", false, "request-1"));

        assertFalse(context.containsKey("conversationHistory"));
        verify(redis, never()).opsForList();
    }
}
