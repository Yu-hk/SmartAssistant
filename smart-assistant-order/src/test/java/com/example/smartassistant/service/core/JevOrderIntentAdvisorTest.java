package com.example.smartassistant.service.core;

import com.example.smartassistant.common.jev.JevDecisionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.net.URI;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JevOrderIntentAdvisorTest {
    @Test
    void jevComponentsCanBeSubclassProxiedBySpringAop() {
        JevDecisionClient client = new JevDecisionClient(new ObjectMapper(), false, "", "jev-latest", 600,
                URI.create("http://127.0.0.1:1/v1/systemone"));
        for (Object target : new Object[]{client, new JevOrderIntentAdvisor(client)}) {
            ProxyFactory factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true);
            assertDoesNotThrow(() -> { factory.getProxy(); });
        }
    }

    @Test
    void onlyHighConfidenceReadOnlyLabelsCanBeReturned() throws Exception {
        JevDecisionClient client = mock(JevDecisionClient.class);
        var mapper = new ObjectMapper();
        when(client.evaluate(anyString(), anyMap(), anyString()))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(mapper.readTree(
                        "{\"intent\":{\"choice\":\"TRACK_LOGISTICS\",\"confidence\":0.95},\"write_request\":{\"noul\":0.01}}"))))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(mapper.readTree(
                        "{\"intent\":{\"choice\":\"QUERY_ORDER\",\"confidence\":0.55},\"write_request\":{\"noul\":0.01}}"))))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(mapper.readTree(
                        "{\"intent\":{\"choice\":\"CREATE_ORDER\",\"confidence\":1.0},\"write_request\":{\"noul\":0.99}}"))))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(mapper.readTree(
                        "{\"intent\":{\"choice\":\"QUERY_ORDER\",\"confidence\":1.0},\"write_request\":{\"noul\":0.99}}"))));
        var advisor = new JevOrderIntentAdvisor(client);
        assertEquals(OrderIntentService.IntentType.TRACK_LOGISTICS,
                advisor.detectReadOnly("物流进度怎么样", "safe-1"));
        assertEquals(OrderIntentService.IntentType.OTHER,
                advisor.detectReadOnly("订单怎么样", "safe-2"));
        assertEquals(OrderIntentService.IntentType.OTHER,
                advisor.detectReadOnly("我要下单", "safe-3"));
        assertEquals(OrderIntentService.IntentType.OTHER,
                advisor.detectReadOnly("现在替我退钱", "safe-4"));
    }
}
