package com.example.smartassistant.consumer.config;

import com.example.smartassistant.consumer.service.core.ConversationPreprocessingService;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.example.smartassistant.consumer.service.sentiment.SentimentAnalysisService;
import com.example.smartassistant.consumer.service.sentiment.SentimentSnapshotStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SentimentExecutionConfigTest {
    @Test void springWiresOptionalAnalyzerDependenciesAndIndependentBoundedExecutor() {
        ThreadPoolExecutor executor;
        try (var context = new AnnotationConfigApplicationContext()) {
            // These are collaborators, not profile beans under wiring test. A
            // bean definition would autowire inherited fields on Mockito mocks.
            context.getBeanFactory().registerSingleton("userProfileService", mock(UserProfileService.class));
            context.getBeanFactory().registerSingleton("sentimentSnapshotStore", mock(SentimentSnapshotStore.class));
            context.register(SentimentExecutionConfig.class, SentimentAnalysisService.class,
                    ConversationPreprocessingService.class);
            context.refresh();
            assertNotNull(context.getBean(ConversationPreprocessingService.class));
            assertEquals(4, context.getBean(SentimentAnalysisService.class).analyze("太慢了").level());
            executor = context.getBean("sentimentExecutor", ThreadPoolExecutor.class);
            assertEquals(2, executor.getMaximumPoolSize());
            assertEquals(32, executor.getQueue().remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, executor.getRejectedExecutionHandler());
        }
        assertTrue(executor.isShutdown());
    }
}
