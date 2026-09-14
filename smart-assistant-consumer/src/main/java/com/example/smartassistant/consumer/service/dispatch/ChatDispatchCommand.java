package com.example.smartassistant.consumer.service.dispatch;

import com.example.smartassistant.consumer.service.sentiment.TurnInsight;

public record ChatDispatchCommand(Long userId, String sessionId, String requestId, String question,
                                  boolean allowAnswerCache, int priority, long expiresAt) {
    public ChatDispatchCommand {
        if (userId == null || userId <= 0 || sessionId == null || sessionId.isBlank()
                || requestId == null || requestId.isBlank() || question == null || question.isBlank()
                || question.length() > 100000 || (priority != 0 && priority != 5) || expiresAt <= 0) {
            throw new IllegalArgumentException("Invalid chat dispatch command");
        }
    }

    public static ChatDispatchCommand create(Long userId, String sessionId, String requestId, String question,
                                             TurnInsight insight, long expiresAt) {
        // Only server-side analysis can grant higher priority; never read the HTTP priority field.
        int priority = insight != null && "ANALYZED".equals(insight.status())
                && "ELEVATED".equals(insight.suggestedPriority()) ? 5 : 0;
        return new ChatDispatchCommand(userId, sessionId, requestId, question,
                insight != null && !insight.bypassAnswerCache(), priority, expiresAt);
    }
}
