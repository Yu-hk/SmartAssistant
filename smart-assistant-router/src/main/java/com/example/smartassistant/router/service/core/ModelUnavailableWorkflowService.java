package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
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
        if (parsed.kind() == BusinessFallbackParser.Kind.CLARIFY) return message(parsed.reply(), true);
        if (request.getUserId() == null || request.getRequestId() == null || request.getRequestId().isBlank())
            return message("请登录后再办理订单业务，本次没有修改订单。", false);
        Map<String, Object> input = new LinkedHashMap<>(parsed.input());
        input.put("_deterministicFallback", true);
        String description;
        if (parsed.kind() == BusinessFallbackParser.Kind.CREATE_ORDER) {
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
            description = "下单 1 件“" + name + "”，金额 " + amount + " 元；收货人：" + input.get("recipient_name")
                    + "；电话：" + input.get("recipient_phone") + "；地址：" + input.get("shipping_address");
        } else description = (parsed.kind() == BusinessFallbackParser.Kind.CANCEL_ORDER ? "取消订单 " : "申请退款 ")
                + input.get("order_id") + "；原因：" + input.get("reason");
        var write = new ExecutionPlan.TaskNode("fallback-write", ExecutionPlan.Domain.ORDER, parsed.kind().name(), description,
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
