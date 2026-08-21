package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.service.core.ModelRoutingService;
import com.example.smartassistant.router.service.evaluation.IntentEvaluationService;
import com.example.smartassistant.router.service.prompt.RouterStageAwareService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void loadsTaskPlannerRolePromptFromClasspathByDefault() throws Exception {
        TaskAnalysisService service = new TaskAnalysisService(
                mock(ModelRoutingService.class), mock(IntentEvaluationService.class),
                null, mock(RouterStageAwareService.class));
        Method method = TaskAnalysisService.class.getDeclaredMethod("resolveSystemPrompt");
        method.setAccessible(true);

        String prompt = (String) method.invoke(service);

        assertTrue(prompt.contains("Role：任务规划专家"));
        assertTrue(prompt.contains("task_steps"));
        assertTrue(prompt.contains("execution_order"));
        assertTrue(prompt.contains("flowchart"));
        assertTrue(prompt.contains("SHIP_ORDER"));
        assertTrue(prompt.contains("TRACK_LOGISTICS"));
        assertTrue(prompt.contains("CONFIRM_DELIVERY"));
        assertTrue(prompt.contains("DISCOVER_PRODUCTS"));
        assertTrue(prompt.contains("ANALYZE_PRODUCT_DATA"));
        assertTrue(prompt.contains("RECOMMEND_PRODUCT"));
        assertTrue(prompt.contains("CREATE_ORDER 必须依赖 RECOMMEND_PRODUCT"));
        assertTrue(prompt.contains("仅输出一个合法 JSON 对象"));
    }
}
