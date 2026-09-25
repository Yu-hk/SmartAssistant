package com.example.smartassistant.consumer.service.sentiment;

/** Request-scoped observations, not a permanent user label or a claim of actual queue promotion. */
public record TurnInsight(String status, Integer level, String label, int confidence,
                          boolean escalated, boolean handoffRequested, String responseStrategy,
                          String suggestedPriority, String reason, long latencyMs, boolean stateRecorded) {
    public static TurnInsight unknown(String reason, long latencyMs) {
        return new TurnInsight("UNKNOWN", null, "未知", 0, false, false,
                "STANDARD", "NORMAL", reason, latencyMs, false);
    }

    public static TurnInsight analyzed(SentimentAnalysisService.SentimentResult result, long latencyMs) {
        boolean empathetic = result.level() >= 3;
        return new TurnInsight("ANALYZED", result.level(), result.name(), result.confidence(), false,
                result.needHandoff(), result.needHandoff() ? "OFFER_HUMAN" : empathetic ? "EMPATHETIC" : "STANDARD",
                result.level() >= 4 ? "ELEVATED" : "NORMAL", "HEURISTIC_ANALYSIS", latencyMs, false);
    }

    public boolean bypassAnswerCache() {
        return !"ANALYZED".equals(status) || level != null && level >= 3 || escalated || handoffRequested;
    }

    public TurnInsight withLatency(long elapsedMs) {
        return new TurnInsight(status, level, label, confidence, escalated, handoffRequested,
                responseStrategy, suggestedPriority, reason, elapsedMs, stateRecorded);
    }

    /** Fixed wording only; never alters the original question or promises a nonexistent human transfer. */
    public String adaptReply(String reply) {
        if (reply == null || reply.isBlank() || !"ANALYZED".equals(status)) return reply;
        if ("STANDARD".equals(responseStrategy) || reply.startsWith("抱歉") || reply.startsWith("非常抱歉")
                || reply.startsWith("很抱歉") || reply.startsWith("对不起")) return reply;
        // Avoid an awkward "抱歉……您好" when adding the current turn's empathy
        // to an otherwise valid answer. Strip only a leading greeting, never facts.
        return "抱歉给您带来不便。" + reply.replaceFirst("^(?:您好|你好)[，,！!。\\s]*", "");
    }
}
