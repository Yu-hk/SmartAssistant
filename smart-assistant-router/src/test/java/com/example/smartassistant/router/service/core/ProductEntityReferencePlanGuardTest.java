package com.example.smartassistant.router.service.core;
import com.example.smartassistant.router.service.taskanalysis.ProductEntityReferencePlanGuard;
import com.example.smartassistant.router.service.agent.*;
import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class ProductEntityReferencePlanGuardTest {
    @Test void domainClaimRepairsGeneralPlanAndCarriesTypedHistoryToRealGraph() {
        var caller = mock(AgentCallerService.class);
        var guard = new ProductEntityReferencePlanGuard(caller);
        var analysis = TaskAnalysisResult.empty();
        analysis.setAnalysisModel("synthetic-model");
        analysis.setIntentCategory("GENERAL");
        analysis.setNeedsClarification(true);
        when(caller.callAgentAndExtractTitles(eq("product"), any(AgentExecutionRequest.class))).thenReturn(
                new AgentCallResult("catalog", List.of(), Map.of(), DomainQualityResult.pass(1, "FACT"), Map.of("handled", true)));
        List<String> history = List.of("用户：A和B", "助手：B、A");
        var repaired = guard.repair("第二款价格？", history, analysis);
        assertThat(repaired.getAnalysisModel()).isEqualTo("synthetic-model");
        assertThat(repaired.isNeedsClarification()).isFalse();
        var graph = RouteExecutionService.buildGraphFromAnalysis("第二款价格？", repaired);
        assertThat(graph).isNotNull();
        assertThat(repaired.getSubIntents().getFirst()).containsEntry("target_agent", "product").containsEntry("access_mode", "READ");
        assertThat(repaired.getSubIntents().getFirst().get("input")).isEqualTo(Map.of("conversationHistory", history));
        var capture = org.mockito.ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(caller).callAgentAndExtractTitles(eq("product"), capture.capture());
        assertThat(capture.getValue().operation()).isEqualTo("RESOLVE_READ_ONLY_PRODUCT");
        assertThat(capture.getValue().input().get("conversationHistory")).isEqualTo(history);
    }
    @Test void unsafeUnclaimedAndFailedQueriesNeverRepair() {
        var caller = mock(AgentCallerService.class);
        var guard = new ProductEntityReferencePlanGuard(caller);
        var analysis = TaskAnalysisResult.empty();
        for (String question : List.of("给第二款下单，价格2000", "退款第二款多少钱", "这个文档里价格是多少", "北京天气", "AirPods价格", "这款价格" + "x".repeat(160)))
            assertThat(guard.repair(question, List.of(), analysis)).isSameAs(analysis);
        verifyNoInteractions(caller);
        when(caller.callAgentAndExtractTitles(eq("product"), any(AgentExecutionRequest.class))).thenReturn(
                new AgentCallResult("", List.of(), Map.of(), DomainQualityResult.unknown(), Map.of("handled", false)));
        assertThat(guard.repair("第二款价格？", List.of(), analysis).getSubIntents()).isEmpty();
        when(caller.callAgentAndExtractTitles(eq("product"), any(AgentExecutionRequest.class))).thenThrow(new IllegalStateException("offline"));
        assertThat(guard.repair("这两款多少钱？", List.of(), analysis).getSubIntents()).isEmpty();
    }
}
