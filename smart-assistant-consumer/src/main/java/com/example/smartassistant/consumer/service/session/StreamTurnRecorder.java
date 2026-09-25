package com.example.smartassistant.consumer.service.session;

import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.consumer.service.infrastructure.RoutingCallLogService;
import com.example.smartassistant.consumer.service.infrastructure.TokenUsageExtractor;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists one streamed turn and commits profile changes only after a successful turn. */
public final class StreamTurnRecorder {
    private static final Logger log = LoggerFactory.getLogger(StreamTurnRecorder.class);
    private final RoutingCallLogService callLogService;

    public StreamTurnRecorder(RoutingCallLogService callLogService) {
        this.callLogService = callLogService;
    }

    public void record(UserProfileService profileService, String rawUserId, String sessionId,
                       String requestId, String message, String agentName, String responseSummary,
                       long startedAt, String status, TokenUsageExtractor.TokenUsage tokenUsage,
                       ToolUsageCache.ToolUsage toolUsage) {
        Long userId = parseUserId(rawUserId);
        callLogService.saveLog(
                userId, sessionId, requestId, message,
                agentName == null || agentName.isBlank() ? "unknown" : agentName,
                "STREAM_ROUTER_SERVICE", System.currentTimeMillis() - startedAt,
                status, responseSummary,
                tokenUsage.promptTokens(), tokenUsage.completionTokens(), tokenUsage.totalTokens(),
                message, toolUsage);
        if (!"SUCCESS".equals(status) || profileService == null
                || userId == null || requestId == null || requestId.isBlank()) {
            return;
        }
        try {
            profileService.commitAfterSuccessfulTurn(userId, requestId);
        } catch (RuntimeException error) {
            log.error("[StreamChat] 调度画像提交失败: requestId={}, error={}", requestId, error.getMessage());
        }
    }

    private static Long parseUserId(String rawUserId) {
        if (rawUserId == null || rawUserId.isBlank() || "anonymous".equalsIgnoreCase(rawUserId)) {
            return null;
        }
        try {
            return Long.valueOf(rawUserId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
