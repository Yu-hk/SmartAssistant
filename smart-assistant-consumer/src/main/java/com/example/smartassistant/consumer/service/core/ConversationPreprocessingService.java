package com.example.smartassistant.consumer.service.core;

import com.example.smartassistant.common.rag.source.UserDocumentContext;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.example.smartassistant.consumer.service.sentiment.SentimentAnalysisService;
import com.example.smartassistant.consumer.service.sentiment.SentimentSnapshotStore;
import com.example.smartassistant.consumer.service.sentiment.TurnInsight;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.*;

/** Shared by SSE and synchronous chat. Profile preparation and sentiment inference overlap. */
@Service
public class ConversationPreprocessingService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConversationPreprocessingService.class);
    private final SentimentAnalysisService analyzer;
    private final SentimentSnapshotStore snapshots;
    private final UserProfileService profiles;
    private final ExecutorService executor;
    private final long timeoutMs;

    public ConversationPreprocessingService(SentimentAnalysisService analyzer, SentimentSnapshotStore snapshots,
            UserProfileService profiles, @Qualifier("sentimentExecutor") ExecutorService executor,
            @Value("${consumer.sentiment.timeout-ms:750}") long timeoutMs) {
        this.analyzer = analyzer;
        this.snapshots = snapshots;
        this.profiles = profiles;
        this.executor = executor;
        this.timeoutMs = Math.max(20, Math.min(3000, timeoutMs));
    }

    public TurnInsight prepare(Long userId, String sessionId, String requestId, String question) {
        long started = System.nanoTime();
        String key = null;
        String owner = UUID.randomUUID().toString();
        boolean claimed = false;
        TurnInsight existing = null;
        try {
            key = snapshots.requestKey(userId, sessionId, requestId, question);
            existing = snapshots.read(key);
            if (existing == null) {
                claimed = snapshots.claim(key, owner);
            }
            if (existing == null && !claimed) {
                while (existing == null && elapsed(started) < timeoutMs) {
                    existing = snapshots.read(key);
                    if (existing == null) Thread.sleep(10);
                }
                if (existing == null) existing = TurnInsight.unknown("ANALYSIS_IN_PROGRESS", elapsed(started));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return TurnInsight.unknown("INTERRUPTED", elapsed(started));
        } catch (RuntimeException unavailable) {
            // Analysis may still run without state; never invent a cross-turn trend.
            log.warn("[Sentiment] Snapshot unavailable; analyzing without history: requestId={}", requestId);
            key = null;
        }
        if (existing != null) return reuse(existing, userId, question, requestId);

        // Do not classify imported evidence as the user's emotional expression.
        String userText = UserDocumentContext.from(question).question();
        Future<SentimentAnalysisService.SentimentResult> future = null;
        TurnInsight insight;
        try {
            try {
                future = executor.submit(() -> analyzer.analyze(userText));
            } catch (RejectedExecutionException overloaded) {
                // Optional profile preparation still starts independently.
            }
            prefetchProfile(userId, question, requestId);
            if (future == null) {
                insight = TurnInsight.unknown("OVERLOADED", elapsed(started));
            } else {
                long remaining = timeoutMs - elapsed(started);
                if (remaining <= 0) throw new TimeoutException();
                insight = TurnInsight.analyzed(future.get(remaining, TimeUnit.MILLISECONDS), elapsed(started));
            }
        } catch (TimeoutException error) {
            insight = TurnInsight.unknown("TIMEOUT", elapsed(started));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            insight = TurnInsight.unknown("INTERRUPTED", elapsed(started));
        } catch (ExecutionException error) {
            insight = TurnInsight.unknown("ANALYSIS_FAILED", elapsed(started));
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
        }
        if ("UNKNOWN".equals(insight.status())) {
            log.info("[Sentiment] Degraded observation: requestId={}, reason={}, latencyMs={}",
                    requestId, insight.reason(), insight.latencyMs());
        }
        if (key != null && claimed) {
            try { return snapshots.commit(key, owner, userId, sessionId, insight); }
            catch (RuntimeException unavailable) {
                log.warn("[Sentiment] Snapshot commit unavailable: requestId={}", requestId);
            }
        }
        return insight;
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    private TurnInsight reuse(TurnInsight insight, Long userId, String question, String requestId) {
        prefetchProfile(userId, question, requestId);
        return insight;
    }

    private void prefetchProfile(Long userId, String question, String requestId) {
        try { profiles.prefetchForRequest(userId, question, requestId); }
        catch (RuntimeException unavailable) {
            log.warn("[Sentiment] Optional profile skipped without affecting emotion analysis: requestId={}", requestId);
        }
    }
}
