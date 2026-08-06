package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.service.core.ModelRoutingService;
import com.example.smartassistant.router.service.evaluation.IntentEvaluationService;
import com.example.smartassistant.router.service.prompt.RouterStageAwareService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskAnalysisPromptTest {
    @Test
    void buildsPromptWithoutRecursiveOverloadLoop() throws Exception {
        IntentRetriever retriever = mock(IntentRetriever.class);
        when(retriever.retrieve("question", 3)).thenReturn(List.of());
        when(retriever.buildIntentSection(anyList())).thenReturn("intent-section");
        TaskAnalysisService service = new TaskAnalysisService(
                mock(ModelRoutingService.class),
                mock(IntentEvaluationService.class),
                retriever,
                mock(RouterStageAwareService.class));
        Field prompt = TaskAnalysisService.class.getDeclaredField("systemPrompt");
        prompt.setAccessible(true);
        prompt.set(service, "base-prompt");
        Method method = TaskAnalysisService.class.getDeclaredMethod(
                "buildDynamicPrompt", String.class, List.class);
        method.setAccessible(true);

        assertEquals("base-prompt\n\nintent-section", method.invoke(service, "question", List.of()));
    }
}
