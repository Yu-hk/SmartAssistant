package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Compatibility adapter for legacy answer-cache callers. A cache hit has no
 * immutable request admission/generation, so it must not read or preload a user
 * profile, issue a personalization model call, or create a derived profile copy.
 * Personalization belongs exclusively to the governed live-request pipeline.
 */
@Service
public class AnswerPersonalizationService {
    public AnswerPersonalizationService(AiChatService ignoredChat,
            @Qualifier("lightChatModel") ChatModel ignoredModel,
            ReactiveStringRedisTemplate ignoredRedis, UserProfileService ignoredProfiles,
            ObjectMapper ignoredMapper, MeterRegistry ignoredMetrics) { }

    public Mono<String> personalizeAnswer(String originalAnswer, String question, String userId) {
        return Mono.justOrEmpty(originalAnswer);
    }

    /** No detached Redis writes or late cache warmup after a user's deletion. */
    public Mono<Void> preloadProfile(String userId) { return Mono.empty(); }

    /** Historical copies are removed by the awaited, owner-scoped cleanup job. */
    public void invalidateProfileCache(String userId) { }

    public String quickRewrite(String originalAnswer) { return originalAnswer; }
}
