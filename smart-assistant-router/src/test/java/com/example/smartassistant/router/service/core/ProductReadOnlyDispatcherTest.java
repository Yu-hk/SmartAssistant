package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.agent.*;
import com.example.smartassistant.router.service.guardrail.*;
import com.example.smartassistant.router.service.taskanalysis.TaskAnalysisService;
import com.example.smartassistant.common.quality.DomainQualityResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ProductReadOnlyDispatcherTest {
    @Test void dispatcherRequiresExplicitDomainClaimAndVerifiedFacts() {
        AgentCallerService caller = mock(AgentCallerService.class);
        var dispatcher = new ProductReadOnlyDispatcher(caller);
        var request = new RouteRequest(12L, "AirPods Pro多少钱？有货吗？", "session", false, "read-request");
        when(caller.callAgentAndExtractTitles(eq("product"), any(com.example.smartassistant.common.agent.protocol.AgentExecutionRequest.class)))
                .thenReturn(new AgentCallResult("未识别", List.of(), Map.of(), DomainQualityResult.unknown(), Map.of("handled", false)));
        assertThat(dispatcher.tryQuery(request, List.of())).isNull();
        when(caller.callAgentAndExtractTitles(eq("product"), any(com.example.smartassistant.common.agent.protocol.AgentExecutionRequest.class)))
                .thenReturn(new AgentCallResult("1999元，库存充足", List.of(), Map.of(), DomainQualityResult.pass(1, "FACT"), Map.of("handled", true)));
        var result = dispatcher.tryQuery(request, List.of());
        assertThat(result.getWorkflowStatus()).isEqualTo(RoutingResult.WorkflowStatus.COMPLETED);
        assertThat(result.getSemanticCacheCategory()).isEqualTo("NONE");
        when(caller.callAgentAndExtractTitles(eq("product"), any(com.example.smartassistant.common.agent.protocol.AgentExecutionRequest.class)))
                .thenReturn(new AgentCallResult("目录故障", List.of(), Map.of(), DomainQualityResult.fail("PRODUCT_CATALOG_UNAVAILABLE"), Map.of()));
        var failed = dispatcher.tryQuery(request, List.of());
        assertThat(failed.getWorkflowStatus()).isEqualTo(RoutingResult.WorkflowStatus.FAILED);
        assertThat(failed.getDomainQuality().isFail()).isTrue();
        assertThat(failed.getResult()).doesNotContain("支付", "退款");
    }
    @Test void realRouterEntrySkipsPlanningForClaimedRead() {
        var analysis = mock(TaskAnalysisService.class);
        var guard = mock(GuardrailService.class);
        var finalizer = mock(RouteFinalizer.class);
        var execution = mock(RouteExecutionService.class);
        var context = mock(RouteContextHelper.class);
        var dispatcher = mock(ProductReadOnlyDispatcher.class);
        var service = new RouterService(null, null, null, analysis, null, guard, finalizer, execution, context);
        ReflectionTestUtils.setField(service, "productReadOnlyDispatcher", dispatcher);
        var request = new RouteRequest(12L, "AirPods Pro多少钱？有货吗？", "s", false, "r");
        when(guard.check(anyString())).thenReturn(GuardrailService.GuardrailCheckResult.notTriggered());
        when(guard.checkEmotion(anyString())).thenReturn(EmotionCheckResult.none());
        when(context.buildContext(request)).thenReturn(Map.of());
        var direct = RoutingResult.builder().result("1999元，库存充足").intentTag("PRODUCT").build();
        when(dispatcher.tryQuery(eq(request), anyList())).thenReturn(direct);
        when(finalizer.finalizeRouting(eq(direct), eq(request), anyString(), any())).thenReturn(direct);
        assertThat(service.route(request)).isSameAs(direct);
        verifyNoInteractions(analysis, execution);
        reset(dispatcher);
        when(guard.check(anyString())).thenReturn(new GuardrailService.GuardrailCheckResult(true, List.of("退款"), List.of(), true, false));
        when(analysis.analyze(anyString(), anyString(), anyList())).thenThrow(
                com.example.smartassistant.common.error.ModelCallFailure.from(new RuntimeException("402: Insufficient Balance")));
        var failure = service.route(request);
        assertThat(failure.getWorkflowStatus()).isEqualTo(RoutingResult.WorkflowStatus.FAILED);
        assertThat(failure.getDomainQuality().getReasonCodes()).contains("MODEL_BILLING_UNAVAILABLE");
        verifyNoInteractions(dispatcher, execution);
    }
    @Test void readFailureDoesNotWarnAboutDuplicatePaymentsButUnknownWriteStillDoes() {
        var read = new SubTaskResult("a", "查价格", "product", "", false);
        read.setStructuredData(Map.of("_accessMode", "READ"));
        assertThat(ResultMerger.requiredFailureReply(List.of(read))).contains("查询").doesNotContain("支付", "退款");
        read.setStructuredData(Map.of());
        assertThat(ResultMerger.requiredFailureReply(List.of(read))).contains("支付", "退款");
    }
}
