package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.prompt.PromptManager;
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
    private final PromptManager promptManager = new PromptManager();
    private final LLMPreferenceExtractor extractor =
            new LLMPreferenceExtractor(aiChatService, lightModel, tokenizer, promptManager);

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
                .entity(LLMPreferenceExtractor.UserInsightReport.class))
                .thenAnswer(invocation -> {
                    Thread.sleep(5_000);
                    return LLMPreferenceExtractor.UserInsightReport.empty("timeout");
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
        Async commitAnnotation = UserProfileService.class
                .getMethod("commitAfterSuccessfulTurn", Long.class, String.class)
                .getAnnotation(Async.class);

        assertNotNull(annotation);
        assertEquals("taskExecutor", annotation.value());
        assertNotNull(commitAnnotation);
        assertEquals("taskExecutor", commitAnnotation.value());
    }

    @Test
    void modelFailureReturnsUnreliableEmptyReportWithoutLegacyFields() {
        ReflectionTestUtils.setField(extractor, "extractionTimeoutMs", 1_000L);
        when(aiChatService.buildChatClient(lightModel)
                .prompt().user(anyString()).call()
                .entity(LLMPreferenceExtractor.UserInsightReport.class))
                .thenThrow(new IllegalStateException("model unavailable"));

        var result = extractor.extract("我喜欢续航长的平板，不要太重");

        assertEquals("KEEP", result.profileUpdate().action());
        assertTrue(!result.commerceAssessment().reliable());
        assertTrue(result.commerceAssessment().limitations().contains("用户画像模型调用失败"));
    }

    @Test
    void rendersEvidenceBoundUserInsightPromptWithStructuredOutput() {
        String prompt = ReflectionTestUtils.invokeMethod(
                extractor, "buildExtractionPrompt", "我喜欢轻薄电脑，不要太重");

        assertNotNull(prompt);
        assertTrue(prompt.contains("电商用户洞察与转化策略分析师"));
        assertTrue(prompt.contains("增量更新"));
        assertTrue(prompt.contains("只输出一个合法 JSON 对象"));
        assertTrue(prompt.contains("我喜欢轻薄电脑，不要太重"));
        assertTrue(prompt.contains("profileUpdate"));
        assertTrue(prompt.contains("conversionStrategies"));
    }

    @Test
    void downgradesUnsupportedHighConfidenceAndRejectsExtremeRadarScores() {
        var raw = new LLMPreferenceExtractor.UserInsightReport(
                new LLMPreferenceExtractor.ProfileUpdate(
                        "UPDATE", java.util.List.of("radarScores"), java.util.List.of(),
                        "补充评分", java.util.List.of("预算五千")),
                java.util.Map.of("values", new LLMPreferenceExtractor.InsightDimension(
                        "重视性价比", java.util.List.of("预算五千"), "高")),
                java.util.List.of(),
                java.util.Map.of("基础画像", 9, "核心动机", 8),
                java.util.List.of(), java.util.List.of(),
                LLMPreferenceExtractor.CommerceAssessment.empty("测试"), java.util.List.of());

        var normalized = raw.normalized();

        assertEquals("中", normalized.insightDimensions().get("values").confidence());
        assertTrue(normalized.radarScores().isEmpty());
    }
}
