package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import org.springframework.stereotype.Service;
import java.util.*;

/** Product owns recognition and facts. Router only dispatches the read-only capability probe. */
@Service
public class ProductReadOnlyDispatcher {
    private final AgentCallerService caller;
    public ProductReadOnlyDispatcher(AgentCallerService caller) { this.caller = caller; }
    public RoutingResult tryQuery(RouteRequest request, List<String> history) {
        // A probe must not grow unbounded prompts or execute any write operation.
        if (request.getQuestion() == null || request.getQuestion().length() > 160) return null;
        try {
            var response = caller.callAgentAndExtractTitles("product", new AgentExecutionRequest(
                    AgentExecutionRequest.CURRENT_VERSION, request.getRequestId(), "catalog-read",
                    String.valueOf(request.getUserId()), "RESOLVE_READ_ONLY_PRODUCT", request.getQuestion(),
                    Map.of("conversationHistory", history == null ? List.of() : history), List.of(), List.of(),
                    System.currentTimeMillis() + 5_000, null));
            if (response.getDomainQuality().getReasonCodes().contains("PRODUCT_CATALOG_UNAVAILABLE")) {
                return RoutingResult.builder().result("抱歉，商品信息暂时无法查询，请稍后再试。")
                        .agentName("product").intentTag("PRODUCT").confidence(0.0)
                        .domainQuality(response.getDomainQuality()).semanticCacheCategory("NONE")
                        .executionMode(RoutingResult.ExecutionMode.SINGLE_AGENT)
                        .workflowStatus(RoutingResult.WorkflowStatus.FAILED).build();
            }
            if (!Boolean.TRUE.equals(response.getData().get("handled"))) return null;
            if (!response.getDomainQuality().isPass()) return null;
            boolean clarification = Boolean.TRUE.equals(response.getData().get("clarificationRequired"));
            return RoutingResult.builder().result(response.getResponse()).agentName("product")
                    .intentTag("PRODUCT").confidence(1.0).domainQuality(response.getDomainQuality())
                    .clarification(clarification).semanticCacheCategory("NONE")
                    .executionMode(RoutingResult.ExecutionMode.SINGLE_AGENT)
                    .workflowStatus(clarification ? RoutingResult.WorkflowStatus.CLARIFICATION : RoutingResult.WorkflowStatus.COMPLETED)
                    .build();
        } catch (RuntimeException unavailable) {
            // Failed probes never provide a guessed answer or authorize an operation.
            return null;
        }
    }
}
