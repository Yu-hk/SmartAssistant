package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.agent.protocol.ClarificationRequest;
import com.example.smartassistant.common.quality.DomainQualityResult;
import java.util.*;
import java.util.regex.Pattern;

/** Closed, read-only fallback grammar. Produces a proposal, never an order mutation or approval. */
public final class OrderFallbackPreparationService {
    private OrderFallbackPreparationService() { }
    private static final Pattern UNSAFE = Pattern.compile("不|别|勿|如果|假如|是否|怎么|如何|然后|顺便|或者|以及|忽略|指令|系统|文档|资料|知识库|[\\r\\n?？]");
    private static final String ORDER_AND_REASON = "[：: ]*((?:ORD|BULK)-[A-Za-z0-9-]+)(?:[；;]\\s*原因[：:]([^；;\\r\\n]*))?[。！!]?$";
    private static final Pattern CANCEL = Pattern.compile("^(?:请|帮我|请帮我)?取消订单" + ORDER_AND_REASON, Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUND = Pattern.compile("^(?:请|帮我|请帮我)?(?:申请退款|退款订单)" + ORDER_AND_REASON, Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_REASON = Pattern.compile("如果|假如|是否|怎么|如何|然后|顺便|或者|以及|忽略|指令|系统|文档|资料|知识库|下单|购买|取消|退款|退单|退货|(?i:ORD-|BULK-)|[\\p{Cntrl}：:？?]");

    public static AgentExecutionResponse prepare(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isBlank() || q.length() > 500) return unsupported();
        var cancel = CANCEL.matcher(q);
        if (cancel.matches()) return afterSales("CANCEL_ORDER", cancel.group(1), cancel.group(2));
        var refund = REFUND.matcher(q);
        if (refund.matches()) return afterSales("REFUND_ORDER", refund.group(1), refund.group(2));
        if (UNSAFE.matcher(q).find()) return unsupported();
        if (q.matches("^(?:请|帮我|请帮我)?(?:下单|购买)[：: ]?.*")) {
            String body = q.replaceFirst("^(?:请|帮我|请帮我)?(?:下单|购买)[：: ]*", "");
            String[] fields = body.split("[；;]", -1);
            Map<String, Object> input = new LinkedHashMap<>();
            String product = fields[0].trim();
            if (!product.isBlank()) input.put("product_name", product);
            Map<String, String> labels = Map.of("数量", "quantity", "收货人", "recipient_name", "电话", "recipient_phone", "地址", "shipping_address");
            for (int i = 1; i < fields.length; i++) {
                String[] pair = fields[i].trim().split("[：:]", 2);
                if (pair.length != 2 || !labels.containsKey(pair[0].trim()) || pair[1].isBlank()) return unsupported();
                if (input.putIfAbsent(labels.get(pair[0].trim()), pair[1].trim()) != null) return unsupported();
            }
            // The fallback supports one item only; never infer quantity from budget or amount.
            if (!"1".equals(input.getOrDefault("quantity", "1")))
                return guidance("备用下单流程目前仅支持单件商品，本次没有创建订单。多件商品请稍后再试或联系人工客服。");
            input.remove("quantity");
            return prepared("CREATE_ORDER", input, "下单 1 件“" + product + "”；收货人：" + input.get("recipient_name")
                    + "；电话：" + input.get("recipient_phone") + "；地址：" + input.get("shipping_address"));
        }
        if (q.matches("^(?:请|帮我|请帮我)?取消订单[。！!]?$"))
            return OrderClarificationService.prepare("CANCEL_ORDER", Map.of());
        if (q.matches("^(?:请|帮我|请帮我)?(?:申请退款|退款订单)[。！!]?$"))
            return OrderClarificationService.prepare("REFUND_ORDER", Map.of());
        if (q.matches("^(?:请|帮我|请帮我)?(?:退单|退货)(?:[：: ]*(?:ORD|BULK)-[A-Za-z0-9-]+)?[。！!]?$"))
            return guidance("请确认您是要取消未付款订单，还是为已付款订单申请退款。目前没有取消订单或提交退款。");
        return unsupported();
    }

    private static AgentExecutionResponse afterSales(String operation, String orderId, String rawReason) {
        String reason = rawReason == null ? "" : rawReason.trim().replaceFirst("[。！!]+$", "").trim();
        if (reason.length() > 100 || UNSAFE_REASON.matcher(reason.replace("重复下单", "重复订购")).find()) return unsupported();
        var input = new LinkedHashMap<String, Object>();
        input.put("order_id", orderId.toUpperCase(Locale.ROOT));
        if (!reason.isBlank()) input.put("reason", reason);
        return prepared(operation, input, ("CANCEL_ORDER".equals(operation) ? "取消订单 " : "申请退款 ")
                + input.get("order_id") + "；原因：" + reason);
    }

    private static AgentExecutionResponse prepared(String operation, Map<String, Object> input, String description) {
        var validation = OrderClarificationService.prepare(operation, input);
        if (validation.data().containsKey(ClarificationRequest.DATA_KEY)) return validation;
        return AgentExecutionResponse.success("资料已核对，仍需确认后才能提交，本次没有修改订单。",
                Map.of("preparedAction", Map.of("operation", operation, "input", input, "description", description)),
                DomainQualityResult.pass(1, "ORDER_FALLBACK_PREPARED"));
    }
    private static AgentExecutionResponse guidance(String text) {
        return AgentExecutionResponse.success(text, Map.of("clarificationRequired", true),
                DomainQualityResult.pass(1, "ORDER_FALLBACK_CLARIFICATION"));
    }
    private static AgentExecutionResponse unsupported() {
        return AgentExecutionResponse.failure("ORDER_FALLBACK_UNSUPPORTED",
                "抱歉，备用流程无法准确处理这次订单请求，本次没有修改订单。请明确要办理的单项业务，或联系人工客服。", false);
    }
}
