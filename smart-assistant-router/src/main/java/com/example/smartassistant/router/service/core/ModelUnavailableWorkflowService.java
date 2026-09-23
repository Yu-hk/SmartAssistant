package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.ClarificationRequest;
import com.example.smartassistant.common.quality.DomainQualityResult;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** Runs only after an observed model failure, never a keyword replacement for the model. */
@Service
public class ModelUnavailableWorkflowService {
    private final BusinessFallbackParser parser;
    private final ProductReadOnlyDispatcher product;
    private final AgentCallerService caller;
    private final RouteExecutionService execution;
    public ModelUnavailableWorkflowService(BusinessFallbackParser parser, ProductReadOnlyDispatcher product,
                                           AgentCallerService caller, RouteExecutionService execution) {
        this.parser = parser; this.product = product; this.caller = caller; this.execution = execution;
    }
    public RoutingResult handle(RouteRequest request, List<String> history, boolean allowWrites) {
        var parsed = parser.parse(request.getQuestion());
        if (parsed.kind() == BusinessFallbackParser.Kind.PRODUCT_QUERY) {
            var result = product.tryQuery(request, history);
            return result == null ? unavailable() : result;
        }
        if (parsed.kind() == BusinessFallbackParser.Kind.UNKNOWN) return unavailable();
        if (!allowWrites) return message("这次处理尚未确认完成，我没有重新提交订单操作。请先查看原请求或联系人工客服核实。", false);
        if (request.getUserId() == null || request.getRequestId() == null || request.getRequestId().isBlank())
            return message("请登录后再办理订单业务，本次没有修改订单。", false);
        // Order owns grammar, required fields, data validation and customer-facing guidance.
        // No mutation or model call is allowed in PREPARE_FALLBACK.
        var preparation = caller.callAgentAndExtractTitles("order", new AgentExecutionRequest(
                AgentExecutionRequest.CURRENT_VERSION, request.getRequestId(), "fallback-prepare", request.getUserId().toString(),
                "PREPARE_FALLBACK", request.getQuestion(), Map.of(), List.of(), List.of(),
                System.currentTimeMillis() + 5000, null));
        var contract = ClarificationRequest.read(preparation.getData().get(ClarificationRequest.DATA_KEY));
        if (contract != null && !"order".equals(contract.domain())) return unavailable();
        if (!preparation.getDomainQuality().isPass()
                || contract != null || Boolean.TRUE.equals(preparation.getData().get("clarificationRequired"))) {
            boolean clarification = preparation.getDomainQuality().isPass()
                    && (contract != null || Boolean.TRUE.equals(preparation.getData().get("clarificationRequired")));
            var result = message(preparation.getResponse(), clarification);
            result.setAgentName("order");
            result.setDomainQuality(preparation.getDomainQuality());
            result.setClarificationRequest(clarification ? contract : null);
            return result;
        }
        if (!(preparation.getData().get("preparedAction") instanceof Map<?, ?> action)
                || !(action.get("operation") instanceof String operation)
                || !Set.of("CREATE_ORDER", "CANCEL_ORDER", "REFUND_ORDER").contains(operation)
                || !(action.get("input") instanceof Map<?, ?> preparedInput)
                || !(action.get("description") instanceof String description) || description.isBlank())
            return unavailable();
        Map<String, Object> input = new LinkedHashMap<>();
        for (var entry : preparedInput.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.startsWith("_")) return unavailable();
            input.put(key, entry.getValue());
        }
        input.put("_deterministicFallback", true);
        if ("CREATE_ORDER".equals(operation)) {
            var quote = caller.callAgentAndExtractTitles("product", new AgentExecutionRequest(
                    AgentExecutionRequest.CURRENT_VERSION, request.getRequestId(), "fallback-quote", request.getUserId().toString(),
                    "RESOLVE_READ_ONLY_PRODUCT", input.get("product_name") + "多少钱？有货吗？",
                    Map.of(), List.of(), List.of(), System.currentTimeMillis() + 5000, null));
            if (!quote.getDomainQuality().isPass() || !(quote.getData().get("orderQuote") instanceof Map<?, ?> fact)
                    || !(fact.get("amount") instanceof Number amount) || new BigDecimal(amount.toString()).signum() <= 0
                    || !(fact.get("productName") instanceof String name) || name.isBlank())
                return message("暂时无法核实这款商品的价格和可售库存，本次没有创建订单。请稍后再试或联系人工客服。", false);
            input.put("product_name", name);
            input.put("amount", new BigDecimal(amount.toString()));
            // Enrich the Order-owned proposal with the verified Product quote for approval.
            description += "\n已核实商品：“" + name + "”，金额 " + amount + " 元。";
        }
        var write = new ExecutionPlan.TaskNode("fallback-write", ExecutionPlan.Domain.ORDER, operation, description,
                input, List.of(), ExecutionPlan.AccessMode.WRITE, List.of(), request.getRequestId() + ":fallback-write",
                true, null, ExecutionPlan.MergePolicy.APPEND);
        var plan = new ExecutionPlan(request.getRequestId(), request.getQuestion(), List.of("用户确认前不得修改订单"), List.of(write));
        return execution.executeDeterministicFallback(plan, request.getUserId());
    }
    static RoutingResult unavailable() { return message("抱歉，智能理解服务暂时不可用，备用流程也未能准确处理这次请求。您可以明确商品名称及要查询的信息，或联系人工客服；本次没有提交新的订单操作。", false); }
    static RoutingResult message(String answer, boolean clarification) {
        return RoutingResult.builder().result(answer).intentTag("ORDER").clarification(clarification)
                .executionMode(RoutingResult.ExecutionMode.BUILTIN).semanticCacheCategory("NONE")
                .workflowStatus(clarification ? RoutingResult.WorkflowStatus.CLARIFICATION : RoutingResult.WorkflowStatus.FAILED)
                .domainQuality(clarification ? DomainQualityResult.pass(1, "FALLBACK_CLARIFICATION") : DomainQualityResult.fail("FALLBACK_UNAVAILABLE")).build();
    }
}
