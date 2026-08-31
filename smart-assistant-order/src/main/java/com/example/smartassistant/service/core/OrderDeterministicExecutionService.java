package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.order.OrderStatus;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.LogisticsDTO;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.service.ApprovalService;
import com.example.smartassistant.routing.contract.WorkflowOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes deterministic, read-only order workflow nodes without entering the ReAct loop.
 *
 * <p>The Router already sends a typed {@code operation} and structured {@code input}. For
 * factual reads, invoking an intent classifier and a multi-turn tool-selection model adds
 * latency without adding business value. This service keeps write operations on the existing
 * approval-protected Agent path and only short-circuits operations whose result can be verified
 * directly from the order domain.</p>
 */
@Service
public class OrderDeterministicExecutionService {

    private static final Logger log = LoggerFactory.getLogger(OrderDeterministicExecutionService.class);
    private static final Set<String> SUPPORTED = Set.of(
            WorkflowOperation.QUERY_ORDER.code(),
            WorkflowOperation.QUERY_ORDER_LIST.code(),
            WorkflowOperation.QUERY_PAYMENT_PENDING.code(),
            WorkflowOperation.TRACK_LOGISTICS.code());
    private static final Pattern ORDER_ID = Pattern.compile(
            "(?i)\\b(?:ORD|BULK)-[A-Z0-9-]+\\b");
    private static final Pattern TRAJECTORY_ENTRY = Pattern.compile("\\{([^}]*)}");

    private final OrderDataProvider orderData;
    private final ApprovalService approvalService;

    public OrderDeterministicExecutionService(OrderDataProvider orderData,
                                              ApprovalService approvalService) {
        this.orderData = orderData;
        this.approvalService = approvalService;
    }

    public boolean supports(String operation) {
        return SUPPORTED.contains(normalizeOperation(operation));
    }

    public AgentExecutionResponse execute(AgentExecutionRequest request) {
        String operation = normalizeOperation(request.operation());
        String orderId = resolveOrderId(request);
        if ("QUERY_ORDER_LIST".equals(operation)
                || ("QUERY_ORDER".equals(operation) && orderId == null)) {
            return queryOrderList(request);
        }
        if (orderId == null) {
            return AgentExecutionResponse.failure(
                    "ORDER_ID_REQUIRED", "请提供需要查询的订单号", false);
        }

        OrderDTO order = orderData.findOrderByOrderId(orderId);
        if (order == null) {
            return AgentExecutionResponse.failure(
                    "ORDER_NOT_FOUND", "未找到订单 " + orderId, false);
        }
        if (!belongsToUser(order, request.userId())) {
            log.warn("[OrderFastPath] 拒绝跨用户订单读取: operation={}, orderId={}, userId={}",
                    operation, orderId, request.userId());
            return AgentExecutionResponse.failure(
                    "ORDER_ACCESS_DENIED", "无权访问该订单", false);
        }

        return switch (operation) {
            case "QUERY_ORDER" -> queryOrder(request, order);
            case "QUERY_PAYMENT_PENDING" -> queryPendingPayment(order);
            case "TRACK_LOGISTICS" -> trackLogistics(order);
            default -> AgentExecutionResponse.failure(
                    "UNSUPPORTED_ORDER_OPERATION", "不支持的订单快速操作: " + operation, false);
        };
    }

    private AgentExecutionResponse queryOrderList(AgentExecutionRequest request) {
        Long userId = parseUserId(request.userId());
        if (userId == null) {
            return AgentExecutionResponse.failure(
                    "USER_ID_REQUIRED", "登录后才能查询订单列表", false);
        }
        int limit = intInput(request.input(), "limit", 10, 1, 20);
        int offset = intInput(request.input(), "offset", 0, 0, Integer.MAX_VALUE);
        String status = resolveStatus(request);
        List<Map<String, Object>> orders = orderData.queryOrdersByUserId(
                userId, status, limit, offset);
        if (orders == null) orders = List.of();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "QUERY_ORDER_LIST");
        data.put("orders", orders);
        data.put("count", orders.size());
        data.put("limit", limit);
        data.put("offset", offset);
        data.put("hasMore", orders.size() >= limit);
        if (status != null) data.put("statusFilter", status);
        data.put("verified", true);
        data.put("criteriaSatisfied", true);

        String answer = formatOrderList(orders, status, offset, limit);
        log.info("[OrderFastPath] 确定性订单列表查询完成: userId={}, status={}, count={}, offset={}",
                userId, status, orders.size(), offset);
        return AgentExecutionResponse.success(answer, data,
                DomainQualityResult.pass(1.0, "DETERMINISTIC_ORDER_LIST_QUERY"));
    }

    private static String formatOrderList(List<Map<String, Object>> orders, String status,
                                          int offset, int limit) {
        if (orders.isEmpty()) {
            return status == null
                    ? "当前没有查询到你的订单。"
                    : "当前没有查询到状态为「" + status + "」的订单。";
        }
        StringBuilder answer = new StringBuilder();
        if (status == null) {
            answer.append("你的订单列表：");
        } else {
            answer.append("你的「").append(status).append("」订单列表：");
        }
        for (int index = 0; index < orders.size(); index++) {
            Map<String, Object> order = orders.get(index);
            answer.append("\n").append(offset + index + 1).append(". 订单 ")
                    .append(display(order.get("orderId")))
                    .append("｜商品：").append(display(order.get("productName")))
                    .append("｜金额：¥").append(display(order.get("amount")))
                    .append("｜状态：").append(display(order.get("status")));
        }
        if (orders.size() >= limit) {
            answer.append("\n如需查看更多，请继续查询下一页。");
        }
        return answer.toString();
    }

    private AgentExecutionResponse queryOrder(AgentExecutionRequest request, OrderDTO order) {
        boolean criteriaSatisfied = expectedStatusMatches(request.question(), order.getStatus());
        Map<String, Object> data = baseOrderData(order);
        data.put("operation", "QUERY_ORDER");
        data.put("verified", true);
        data.put("criteriaSatisfied", criteriaSatisfied);

        StringBuilder answer = new StringBuilder()
                .append("订单 ").append(order.getOrderId()).append(" 查询成功。\n")
                .append("商品：").append(order.getProductName()).append("\n")
                .append("金额：¥").append(order.getAmount()).append("\n")
                .append("状态：").append(order.getStatus());
        if (order.getPaymentMethod() != null && !order.getPaymentMethod().isBlank()) {
            answer.append("\n支付方式：").append(order.getPaymentMethod());
        }

        log.info("[OrderFastPath] 确定性订单查询完成: orderId={}, status={}, criteriaSatisfied={}",
                order.getOrderId(), order.getStatus(), criteriaSatisfied);
        return AgentExecutionResponse.success(answer.toString(), data,
                DomainQualityResult.pass(1.0, "DETERMINISTIC_ORDER_QUERY"));
    }

    private AgentExecutionResponse queryPendingPayment(OrderDTO order) {
        ApprovalService.PendingApproval pending =
                approvalService.getPendingApproval(order.getOrderId(), "payment");
        boolean exists = pending != null;
        Map<String, Object> data = baseOrderData(order);
        data.put("operation", "QUERY_PAYMENT_PENDING");
        data.put("actionType", "payment");
        data.put("pendingApprovalExists", exists);
        data.put("approvalStatus", exists ? "PENDING" : "NONE");
        data.put("verified", true);
        // QUERY_PAYMENT_PENDING succeeds once the domain has verified whether a pending
        // approval exists. A negative fact (NONE) is still a successful query result;
        // pendingApprovalExists carries the business value separately.
        data.put("criteriaSatisfied", true);

        String answer = exists
                ? "订单 " + order.getOrderId() + " 存在待确认的支付操作。\n" + pending.getReason()
                : "订单 " + order.getOrderId() + " 当前不存在待确认的支付操作。";
        log.info("[OrderFastPath] 确定性支付确认项查询完成: orderId={}, exists={}",
                order.getOrderId(), exists);
        return AgentExecutionResponse.success(answer, data,
                DomainQualityResult.pass(1.0, "DETERMINISTIC_PAYMENT_APPROVAL_QUERY"));
    }

    private AgentExecutionResponse trackLogistics(OrderDTO order) {
        LogisticsDTO logistics = orderData.findLogisticsByOrderId(order.getOrderId());
        if (logistics == null) {
            return AgentExecutionResponse.failure(
                    "LOGISTICS_NOT_FOUND", "订单 " + order.getOrderId() + " 暂无物流信息", false);
        }

        Map<String, Object> data = baseOrderData(order);
        data.put("operation", "TRACK_LOGISTICS");
        putIfNotNull(data, "trackingNo", logistics.getTrackingNo());
        putIfNotNull(data, "companyName", logistics.getCompanyName());
        putIfNotNull(data, "logisticsStatus", logistics.getStatus());
        putIfNotNull(data, "logisticsDetail", logistics.getLogisticsDetail());
        putIfNotNull(data, "logisticsTime", logistics.getLogisticsTime());
        data.put("verified", true);
        data.put("criteriaSatisfied", true);

        StringBuilder answer = new StringBuilder()
                .append("订单 ").append(order.getOrderId()).append(" 物流查询成功。\n")
                .append("物流公司：").append(display(logistics.getCompanyName())).append("\n")
                .append("运单号：").append(display(logistics.getTrackingNo())).append("\n")
                .append("物流状态：").append(display(logistics.getStatus()));
        appendTrajectory(answer, logistics.getLogisticsDetail());
        answer.append("\n预计送达时间：暂无可靠数据");

        log.info("[OrderFastPath] 确定性物流查询完成: orderId={}, trackingNo={}, status={}",
                order.getOrderId(), logistics.getTrackingNo(), logistics.getStatus());
        return AgentExecutionResponse.success(answer.toString(), data,
                DomainQualityResult.pass(1.0, "DETERMINISTIC_LOGISTICS_QUERY"));
    }

    private static void appendTrajectory(StringBuilder answer, String detail) {
        if (detail == null || detail.isBlank() || "[]".equals(detail.trim())) {
            answer.append("\n物流轨迹：暂无轨迹数据");
            return;
        }
        Matcher matcher = TRAJECTORY_ENTRY.matcher(detail);
        boolean found = false;
        while (matcher.find()) {
            String entry = matcher.group(1);
            String time = jsonField(entry, "time");
            String location = jsonField(entry, "location");
            String description = jsonField(entry, "desc");
            if (!found) answer.append("\n物流轨迹：");
            answer.append("\n- ").append(String.join(" ",
                    java.util.stream.Stream.of(time, location, description)
                            .filter(value -> value != null && !value.isBlank())
                            .toList()));
            found = true;
        }
        if (!found) answer.append("\n物流轨迹：").append(detail);
    }

    private static String jsonField(String objectBody, String field) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
                .matcher(objectBody);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "暂无可靠数据" : value;
    }

    private static String display(Object value) {
        return value == null || value.toString().isBlank() ? "暂无可靠数据" : value.toString();
    }

    private static Map<String, Object> baseOrderData(OrderDTO order) {
        Map<String, Object> data = new LinkedHashMap<>();
        putIfNotNull(data, "orderId", order.getOrderId());
        putIfNotNull(data, "status", order.getStatus());
        putIfNotNull(data, "productName", order.getProductName());
        putIfNotNull(data, "amount", order.getAmount());
        if (order.getPaymentMethod() != null) data.put("paymentMethod", order.getPaymentMethod());
        return data;
    }

    private static void putIfNotNull(Map<String, Object> data, String key, Object value) {
        if (value != null) data.put(key, value);
    }

    private static boolean belongsToUser(OrderDTO order, String requestUserId) {
        if (order.getUserId() == null || requestUserId == null || requestUserId.isBlank()) {
            return false;
        }
        try {
            return order.getUserId().equals(Long.valueOf(requestUserId));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String resolveOrderId(AgentExecutionRequest request) {
        for (String key : new String[]{"order_id", "orderId", "orderNo", "order_no"}) {
            Object value = request.input().get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim().toUpperCase(Locale.ROOT);
            }
        }
        Matcher matcher = ORDER_ID.matcher(request.question() != null ? request.question() : "");
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }

    private static Long parseUserId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int intInput(Map<String, Object> input, String key, int fallback,
                                int minimum, int maximum) {
        Object value = input.get(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.toString());
            return Math.max(minimum, Math.min(maximum, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String resolveStatus(AgentExecutionRequest request) {
        Object explicit = request.input().get("status");
        if (explicit != null) {
            var status = OrderStatus.from(explicit.toString());
            if (status.isPresent()) {
                return status.get().value();
            }
        }
        String question = request.question() != null ? request.question() : "";
        return OrderStatus.firstMentionedIn(question).map(OrderStatus::value).orElse(null);
    }

    private static boolean expectedStatusMatches(String question, String actualStatus) {
        String value = question != null ? question.replaceAll("\\s+", "") : "";
        // "是否已支付、是否已发货" lists fields to inspect; it does not assert either
        // status. Remove these interrogative alternatives before evaluating explicit status
        // expectations such as "确认仍为待付款".
        value = value.replaceAll("是否(?:已经|已)?(?:付款|支付|发货|签收|完成|取消)", "");
        return OrderStatus.firstMentionedIn(value)
                .map(expected -> expected.matches(actualStatus))
                .orElse(true);
    }

    private static String normalizeOperation(String operation) {
        return operation == null ? "" : operation.trim().toUpperCase(Locale.ROOT);
    }
}
