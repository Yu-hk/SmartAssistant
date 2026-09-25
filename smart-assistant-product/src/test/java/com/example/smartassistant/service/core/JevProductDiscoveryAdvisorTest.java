package com.example.smartassistant.service.core;

import com.example.smartassistant.common.jev.JevDecisionClient;
import com.example.smartassistant.spi.InMemoryProductBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JevProductDiscoveryAdvisorTest {
    @Test
    void widensOnlyUncertainReadOnlyDiscoveryNotNamedProductFacts() throws Exception {
        JevDecisionClient client = mock(JevDecisionClient.class);
        var answer = new ObjectMapper().readTree("{\"discovery\":{\"noul\":0.96}}");
        when(client.evaluate(anyString(), anyMap(), anyString()))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(answer)));
        var service = new ProductDiscoveryService(new InMemoryProductBackend());
        ReflectionTestUtils.setField(service, "jevAdvisor", new JevProductDiscoveryAdvisor(client));

        assertTrue(service.supports("我在找耳机", "discovery-request"));
        assertFalse(service.supports("AirPods Pro 的价格是多少", "fact-request"));
        verify(client, times(1)).evaluate(anyString(), anyMap(), eq("discovery-request"));
    }

    @Test
    void lowConfidenceSignalCannotWidenDiscovery() throws Exception {
        JevDecisionClient client = mock(JevDecisionClient.class);
        var answer = new ObjectMapper().readTree("{\"discovery\":{\"noul\":0.53}}");
        when(client.evaluate(anyString(), anyMap(), anyString()))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(answer)));
        var advisor = new JevProductDiscoveryAdvisor(client);
        assertFalse(advisor.suggestsDiscovery("想找耳机", "weak-request"));
    }
}
