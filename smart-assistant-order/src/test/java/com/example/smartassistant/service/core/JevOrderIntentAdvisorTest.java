package com.example.smartassistant.service.core;

import com.example.smartassistant.common.jev.JevDecisionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JevOrderIntentAdvisorTest {
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
