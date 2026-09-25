package com.example.smartassistant.service.core;

import com.example.smartassistant.common.jev.JevDecisionClient;
import com.example.smartassistant.common.jev.JevQuestionCatalog;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Semantic hint for uncertain, read-only product discovery; never supplies catalog facts. */
@Component
public final class JevProductDiscoveryAdvisor {
    private static final Map<String, Object> QUESTIONS = JevQuestionCatalog.load(
            JevProductDiscoveryAdvisor.class, "/jev/product-discovery-questions.json");
    private final JevDecisionClient client;

    public JevProductDiscoveryAdvisor(JevDecisionClient client) {
        this.client = client;
    }

    public boolean suggestsDiscovery(String question, String requestId) {
        return client.evaluate(question, QUESTIONS, requestId)
                .map(decision -> decision.noul("discovery") >= 0.90)
                .orElse(false);
    }
}
