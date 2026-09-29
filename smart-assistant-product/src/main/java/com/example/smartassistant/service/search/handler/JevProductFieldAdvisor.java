package com.example.smartassistant.service.search.handler;

import com.example.smartassistant.common.jev.JevDecisionClient;
import com.example.smartassistant.common.jev.JevQuestionCatalog;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;

/** Read-only, multi-label field hint; never supplies product facts or filters. */
@Component
public class JevProductFieldAdvisor {
    private static final Map<String, Object> QUESTIONS = JevQuestionCatalog.load(
            JevProductFieldAdvisor.class, "/jev/product-field-questions.json");
    private final JevDecisionClient client;

    public JevProductFieldAdvisor(JevDecisionClient client) { this.client = client; }

    public EnumSet<ProductField> suggest(String question) {
        return client.evaluate(question, QUESTIONS, null).map(decision -> {
            EnumSet<ProductField> fields = EnumSet.noneOf(ProductField.class);
            if (decision.noul("identity") >= 0.90) fields.add(ProductField.IDENTITY);
            if (decision.noul("purpose") >= 0.90) fields.add(ProductField.PURPOSE);
            if (decision.noul("feature") >= 0.90) fields.add(ProductField.FEATURE);
            return fields;
        }).orElseGet(() -> EnumSet.noneOf(ProductField.class));
    }

    public enum ProductField { IDENTITY, PURPOSE, FEATURE }
}
