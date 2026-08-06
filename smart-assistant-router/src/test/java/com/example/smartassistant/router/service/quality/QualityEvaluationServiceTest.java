package com.example.smartassistant.router.service.quality;

import com.example.smartassistant.router.service.core.ModelRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QualityEvaluationServiceTest {

    @Mock ModelRoutingService modelRoutingService;
    QualityEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new QualityEvaluationService(modelRoutingService);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "threshold", 0.6);
        ReflectionTestUtils.setField(service, "reflectionLowerBound", 0.5);
        ReflectionTestUtils.setField(service, "reflectionUpperBound", 0.8);
        ReflectionTestUtils.setField(service, "maxEvaluationLength", 6000);
        ReflectionTestUtils.setField(service, "systemPrompt",
                "hallucination: 1.0 表示没有幻觉，0.0 表示明显捏造，分数越高越好");
    }

    @Test
    void lowReflectionScoreIsRejectedInsteadOfSkipped() {
        var result = service.evaluate("问题", "回答", 0.4);

        assertTrue(result.isCompleted());
        assertTrue(!result.isPassing(0.6));
        verify(modelRoutingService, never()).call(contains("hallucination"), contains("问题"));
    }

    @Test
    void malformedJudgeResponseIsAnExplicitFailure() {
        when(modelRoutingService.call(contains("hallucination"), contains("问题")))
                .thenReturn("not-json");

        var result = service.evaluate("问题", "回答", 0.7);

        assertTrue(result.isFailed());
    }
}
