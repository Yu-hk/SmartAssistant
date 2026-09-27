package com.example.smartassistant.service.core;

import com.example.smartassistant.common.jev.JevDecisionClient;
import com.example.smartassistant.common.jev.JevQuestionCatalog;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Model-failure fallback for read-only intent hints, not permission to mutate orders. */
@Component
public class JevOrderIntentAdvisor {
    private static final Map<String, Object> QUESTIONS = JevQuestionCatalog.load(
            JevOrderIntentAdvisor.class, "/jev/order-read-intent-questions.json");
    private final JevDecisionClient client;

    public JevOrderIntentAdvisor(JevDecisionClient client) {
        this.client = client;
    }

    public OrderIntentService.IntentType detectReadOnly(String message, String requestId) {
        return client.evaluate(message, QUESTIONS, requestId).map(decision -> {
            if (decision.confidence("intent") < 0.90 || !(decision.noul("write_request") <= 0.10))
                return OrderIntentService.IntentType.OTHER;
            return switch (decision.choice("intent")) {
                case "QUERY_ORDER" -> OrderIntentService.IntentType.QUERY_ORDER;
                case "TRACK_LOGISTICS" -> OrderIntentService.IntentType.TRACK_LOGISTICS;
                case "REFUND_POLICY" -> OrderIntentService.IntentType.REFUND_POLICY;
                case "ORDER_GUIDANCE" -> OrderIntentService.IntentType.ORDER_GUIDANCE;
                default -> OrderIntentService.IntentType.OTHER;
            };
        }).orElse(OrderIntentService.IntentType.OTHER);
    }
}
