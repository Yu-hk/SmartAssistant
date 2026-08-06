package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.RouteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteContextHelperTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ListOperations<String, String> listOperations;

    RouteContextHelper helper;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        helper = new RouteContextHelper(redisTemplate, 10, 3600);
    }

    @Test
    void loadsMostRecentMessagesInsteadOfOldestMessages() {
        when(listOperations.size("chat:history:s1")).thenReturn(12L);
        when(listOperations.range("chat:history:s1", 2, 11))
                .thenReturn(List.of("用户：最近的问题"));

        var context = helper.buildContext(RouteRequest.builder()
                .userId(7L).question("追问").sessionId("s1").build());

        assertEquals(List.of("用户：最近的问题"), context.get("conversationHistory"));
    }

    @Test
    void appendsBothSidesAndBoundsHistory() {
        helper.appendConversation("s1", "办公笔记本怎么选", "优先看处理器和内存");

        verify(listOperations).rightPush("chat:history:s1", "用户：办公笔记本怎么选");
        verify(listOperations).rightPush("chat:history:s1", "助手：优先看处理器和内存");
        verify(listOperations).trim("chat:history:s1", -10, -1);
        verify(redisTemplate).expire("chat:history:s1", 3600, TimeUnit.SECONDS);
    }
}
