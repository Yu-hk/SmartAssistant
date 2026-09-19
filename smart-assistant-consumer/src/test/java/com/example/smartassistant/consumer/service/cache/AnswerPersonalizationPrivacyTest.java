package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnswerPersonalizationPrivacyTest {
    @Test void legacyCacheCallsNeverReadWarmOrPersonalizeProfiles() {
        var chat=mock(AiChatService.class);var model=mock(ChatModel.class);
        var redis=mock(ReactiveStringRedisTemplate.class);var profiles=mock(UserProfileService.class);
        var mapper=mock(ObjectMapper.class);var metrics=mock(MeterRegistry.class);
        var service=new AnswerPersonalizationService(chat,model,redis,profiles,mapper,metrics);
        for(String user:new String[]{null,"anonymous","42","-1","invalid"}) {
            assertEquals("原始事实",service.personalizeAnswer("原始事实","问题",user).block());
            assertNull(service.preloadProfile(user).block());
            service.invalidateProfileCache(user);
        }
        assertNull(service.personalizeAnswer(null,"问题","42").block());
        assertEquals("原始事实",service.quickRewrite("原始事实"));
        verifyNoInteractions(chat,model,redis,profiles,mapper,metrics);
    }
    @Test void oldAnswerCacheCannotBypassLiveRoutingOrStoreItsResponse() {
        var redis=mock(ReactiveStringRedisTemplate.class);var semantic=mock(SemanticCacheService.class);
        var personalization=mock(AnswerPersonalizationService.class);
        var cache=new AnswerCacheService(redis,semantic,personalization,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var result=cache.getAnswerWithCache("42","question",()->{count.incrementAndGet();return reactor.core.publisher.Mono.just("current answer");});
        assertEquals(0,count.get());assertEquals("current answer",result.block());assertEquals(1,count.get());
        verifyNoInteractions(redis,semantic,personalization);
    }
    @Test void retiredSemanticCacheNeverReadsProfilesOrStoresVectorPayloads() {
        var vector=mock(org.springframework.ai.vectorstore.VectorStore.class);var lookup=mock(VectorSearchCacheService.class);
        var groups=mock(UserGroupingService.class);
        var cache=new SemanticCacheService(vector,lookup,groups,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertNull(cache.searchSimilarAnswer("question","42").block());
        assertNull(cache.storeAnswer("question","answer","42").block());
        verifyNoInteractions(vector,lookup,groups);
    }
}
