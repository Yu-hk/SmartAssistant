package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingDecisionPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishesMultiAgentMetadataWithoutSyntheticAgentName() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForList()).thenReturn(lists);
        ObjectMapper mapper = new ObjectMapper();
        RoutingDecisionPublisher publisher = new RoutingDecisionPublisher(provider, mapper);
        RoutingResult result = RoutingResult.builder()
                .result("已查询商品并生成订单")
                .executionMode(RoutingResult.ExecutionMode.MULTI_AGENT)
                .participatingAgents(List.of("product", "order"))
                .workflowStatus(RoutingResult.WorkflowStatus.COMPLETED)
                .confidence(0.8)
                .build();

        publisher.publish("request-multi", result, null, null);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(RoutingKeys.fullDecision("request-multi")),
                json.capture(), eq(Duration.ofSeconds(120)));
        Map<String, Object> decision = mapper.readValue(
                json.getValue(), new TypeReference<>() {});
        assertFalse(decision.containsKey("agentName"));
        assertEquals("MULTI_AGENT", decision.get("executionMode"));
        assertEquals(List.of("product", "order"), decision.get("participatingAgents"));
        assertEquals("COMPLETED", decision.get("workflowStatus"));
    }
}
