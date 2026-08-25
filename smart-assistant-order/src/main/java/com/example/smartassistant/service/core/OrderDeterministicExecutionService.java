package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.LogisticsDTO;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.service.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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
            "QUERY_ORDER", "QUERY_PAYMENT_PENDING", "TRACK_LOGISTICS");
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

    private static boolean expectedStatusMatches(String question, String actualStatus) {
        String value = question != null ? question.replaceAll("\\s+", "") : "";
        // "是否已支付、是否已发货" lists fields to inspect; it does not assert either
        // status. Remove these interrogative alternatives before evaluating explicit status
        // expectations such as "确认仍为待付款".
        value = value.replaceAll("是否(?:已经|已)?(?:付款|支付|发货|签收|完成|取消)", "");
        if (value.contains("待付款")) return "待付款".equals(actualStatus);
        if (value.contains("待发货") || value.contains("已支付")) return "待发货".equals(actualStatus);
        if (value.contains("已发货")) return "已发货".equals(actualStatus);
        if (value.contains("已签收") || value.contains("已完成")) return "已签收".equals(actualStatus);
        if (value.contains("已取消")) return "已取消".equals(actualStatus);
        if (value.contains("退款中")) return "退款中".equals(actualStatus);
        return true;
    }

    private static String normalizeOperation(String operation) {
        return operation == null ? "" : operation.trim().toUpperCase(Locale.ROOT);
    }
}
