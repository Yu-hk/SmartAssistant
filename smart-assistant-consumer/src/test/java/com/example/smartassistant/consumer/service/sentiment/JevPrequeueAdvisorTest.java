package com.example.smartassistant.consumer.service.sentiment;

import com.example.smartassistant.common.jev.JevDecisionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JevPrequeueAdvisorTest {
    private static TurnInsight neutral() {
        return TurnInsight.analyzed(new SentimentAnalysisService.SentimentResult(
                2, "中性", "正常回复", false, false, 50), 0);
    }

    @Test
    void deterministicFinancialRiskPromotesEvenWhenJevUnavailable() {
        JevDecisionClient client = mock(JevDecisionClient.class);
        when(client.evaluate(anyString(), anyMap(), anyString())).thenReturn(Optional.empty());
        TurnInsight result = new JevPrequeueAdvisor(client)
                .augment("这笔订单被重复扣款了", "risk-request", neutral());
        assertEquals("ELEVATED", result.suggestedPriority());
        assertEquals("DETERMINISTIC_RISK", result.reason());
        assertEquals(2, result.level());
    }

    @Test
    void highConfidenceUrgencyPromotesWithoutInventingBusinessOutcome() throws Exception {
        JevDecisionClient client = mock(JevDecisionClient.class);
        var answers = new ObjectMapper().readTree("{\"frustration\":{\"noul\":0.91},"
                + "\"urgency\":{\"score\":1.9,\"confidence\":0.98}}");
        when(client.evaluate(anyString(), anyMap(), anyString()))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(answers)));
        TurnInsight result = new JevPrequeueAdvisor(client)
                .augment("多次联系后仍未解决", "urgent-request", neutral());
        assertEquals("ELEVATED", result.suggestedPriority());
        assertEquals(4, result.level());
        assertFalse(result.handoffRequested());
    }

    @Test
    void uncertainScoreDoesNotPromoteQueue() throws Exception {
        JevDecisionClient client = mock(JevDecisionClient.class);
        var answers = new ObjectMapper().readTree("{\"frustration\":{\"noul\":0.2},"
                + "\"urgency\":{\"score\":1.8,\"confidence\":0.32}}");
        when(client.evaluate(anyString(), anyMap(), anyString()))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(answers)));
        TurnInsight result = new JevPrequeueAdvisor(client)
                .augment("请帮我查一下", "uncertain-request", neutral());
        assertEquals("NORMAL", result.suggestedPriority());
    }

    @Test
    void urgentScoreWithoutEmotionOrBusinessRiskCannotPromoteQueue() throws Exception {
        JevDecisionClient client = mock(JevDecisionClient.class);
        var answers = new ObjectMapper().readTree("{\"frustration\":{\"noul\":0.05},"
                + "\"urgency\":{\"score\":2.0,\"confidence\":1.0}}");
        when(client.evaluate(anyString(), anyMap(), anyString()))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(answers)));
        TurnInsight result = new JevPrequeueAdvisor(client)
                .augment("请问耳机的售价", "ordinary-request", neutral());
        assertEquals("NORMAL", result.suggestedPriority());
    }

    @Test
    void jevCanIdentifyNegativeEmotionWhenBaselineModelFails() throws Exception {
        JevDecisionClient client = mock(JevDecisionClient.class);
        var answers = new ObjectMapper().readTree("{\"frustration\":{\"noul\":0.95},"
                + "\"urgency\":{\"score\":1.0,\"confidence\":0.9}}");
        when(client.evaluate(anyString(), anyMap(), anyString()))
                .thenReturn(Optional.of(new JevDecisionClient.Decision(answers)));
        TurnInsight result = new JevPrequeueAdvisor(client)
                .augment("多次联系后仍未解决", "fallback-request", TurnInsight.unknown("ANALYSIS_FAILED", 0));
        assertEquals("ANALYZED", result.status());
        assertEquals(4, result.level());
        assertEquals("ELEVATED", result.suggestedPriority());
    }
}
