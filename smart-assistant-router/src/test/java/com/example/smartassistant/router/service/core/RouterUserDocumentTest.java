package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.quality.*;
import com.example.smartassistant.common.rag.source.*;
import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.guardrail.*;
import com.example.smartassistant.router.service.rag.RouterRagService;
import com.example.smartassistant.router.service.taskanalysis.TaskAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouterUserDocumentTest {
    @Test void sourceScopePrecedesPlanningHistoryAndForcedRag() {
        var guard = mock(GuardrailService.class);
        when(guard.check(anyString())).thenReturn(new GuardrailService.GuardrailCheckResult(
                true, java.util.List.of(), java.util.List.of(), true, true));
        when(guard.checkEmotion(anyString())).thenReturn(EmotionCheckResult.none());
        var rag = mock(RouterRagService.class);
        var planner = mock(TaskAnalysisService.class);
        var context = mock(RouteContextHelper.class);
        var executor = mock(RouteExecutionService.class);
        var finalizer = mock(RouteFinalizer.class);
        when(finalizer.finalizeRouting(any(), any(), anyString(), any())).thenAnswer(call -> call.getArgument(0));
        var reader = mock(UserDocumentQaService.class);
        when(reader.answer(any())).thenReturn(DomainAgentResponse.of("续航30小时", DomainQualityResult.pass(1, "DOC")));
        var router = new RouterService(null, rag, null, planner, null, guard, finalizer, executor, context);
        ReflectionTestUtils.setField(router, "userDocumentQaService", reader);
        var result = router.route(RouteRequest.builder().question("仅依据资料：“续航30小时。”回答").build());
        assertEquals("续航30小时", result.getResult());
        assertEquals(1.0, result.getConfidence());
        assertTrue(result.getDisableTools());
        assertEquals("NONE", result.getSemanticCacheCategory());
        verifyNoInteractions(rag, planner, context, executor);
    }
}
