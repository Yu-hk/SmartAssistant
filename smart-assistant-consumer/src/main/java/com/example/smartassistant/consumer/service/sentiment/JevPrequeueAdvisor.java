package com.example.smartassistant.consumer.service.sentiment;

import com.example.smartassistant.common.jev.JevDecisionClient;
import com.example.smartassistant.common.jev.JevQuestionCatalog;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/** Pre-MQ advisory only: bounded Jev signals plus a deterministic risk floor. */
@Component
public class JevPrequeueAdvisor {
    private static final Map<String, Object> QUESTIONS = JevQuestionCatalog.load(
            JevPrequeueAdvisor.class, "/jev/prequeue-questions.json");
    private static final Pattern RISK = loadRiskPattern();
    private final JevDecisionClient client;

    public JevPrequeueAdvisor(JevDecisionClient client) {
        this.client = client;
    }

    public TurnInsight augment(String userText, String requestId, TurnInsight baseline) {
        boolean ruleRisk = userText != null && RISK.matcher(userText).find();
        var observation = client.evaluate(userText, QUESTIONS, requestId);
        if (observation.isEmpty() && !ruleRisk) return baseline;

        double frustration = observation.map(value -> value.noul("frustration")).orElse(Double.NaN);
        double urgency = observation.map(value -> value.score("urgency")).orElse(Double.NaN);
        double confidence = observation.map(value -> value.confidence("urgency")).orElse(Double.NaN);
        boolean negative = frustration >= 0.85 && (baseline.level() == null || baseline.level() < 4);
        boolean jevUrgent = urgency >= 1.5 && confidence >= 0.75;
        // The model score alone cannot promote a request; require observed
        // negative emotion or a deterministic business-risk signal as well.
        boolean highPriority = ruleRisk || negative || (jevUrgent && frustration >= 0.60);
        if (!negative && !highPriority) return baseline;
        return new TurnInsight(negative ? "ANALYZED" : baseline.status(), negative ? 4 : baseline.level(),
                negative ? "负面" : baseline.label(),
                negative ? Math.max(baseline.confidence(), (int) Math.round(frustration * 100)) : baseline.confidence(),
                baseline.escalated(), baseline.handoffRequested(),
                negative && !baseline.handoffRequested() ? "EMPATHETIC" : baseline.responseStrategy(),
                highPriority || "ELEVATED".equals(baseline.suggestedPriority()) ? "ELEVATED" : "NORMAL",
                ruleRisk ? "DETERMINISTIC_RISK" : jevUrgent ? "JEV_URGENCY" : "JEV_NEGATIVE_EMOTION",
                baseline.latencyMs(), baseline.stateRecorded());
    }

    private static Pattern loadRiskPattern() {
        Properties properties = new Properties();
        try (var stream = JevPrequeueAdvisor.class.getResourceAsStream("/jev/prequeue-risk.properties")) {
            if (stream == null) throw new IllegalStateException("Missing prequeue risk policy");
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String expression = properties.getProperty("risk.pattern");
            if (expression == null || expression.isBlank()) throw new IllegalStateException("Empty prequeue risk policy");
            return Pattern.compile(expression);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read prequeue risk policy", error);
        }
    }
}
