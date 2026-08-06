package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LLMPreferenceExtractorTest {

    private final AiChatService aiChatService = mock(AiChatService.class, RETURNS_DEEP_STUBS);
    private final ChatModel lightModel = mock(ChatModel.class);
    private final ChineseTokenizer tokenizer = mock(ChineseTokenizer.class);
    private final LLMPreferenceExtractor extractor =
            new LLMPreferenceExtractor(aiChatService, lightModel, tokenizer);

    @AfterEach
    void tearDown() {
        extractor.shutdownExecutor();
    }

    @Test
    void slowModelFallsBackWithinConfiguredBudget() {
        ReflectionTestUtils.setField(extractor, "extractionTimeoutMs", 50L);
        when(aiChatService.buildChatClient(lightModel)
                .prompt()
                .user(anyString())
                .call()
                .entity(LLMPreferenceExtractor.ExtractedPreferences.class))
                .thenAnswer(invocation -> {
                    Thread.sleep(5_000);
                    return LLMPreferenceExtractor.ExtractedPreferences.empty();
                });

        long started = System.nanoTime();
        var result = extractor.extract("我更看重续航和便携");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertNotNull(result);
        assertTrue(elapsedMs < 1_000, "偏好提取不应阻塞主链路: " + elapsedMs + "ms");
    }

    @Test
    void userProfileAsyncEntryUsesDedicatedExecutor() throws Exception {
        Async annotation = UserProfileService.class
                .getMethod("extractAndUpdatePreferencesAsync", Long.class, String.class, String.class)
                .getAnnotation(Async.class);

        assertNotNull(annotation);
        assertEquals("taskExecutor", annotation.value());
    }
}
