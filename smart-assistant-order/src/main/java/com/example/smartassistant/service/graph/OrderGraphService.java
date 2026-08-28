package com.example.smartassistant.service.graph;

import com.example.smartassistant.common.order.OrderStatus;

import com.example.smartassistant.entity.OrderEntity;
import com.example.smartassistant.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * P3 订单关联图谱服务。
 * <p>
 * 构建用户→订单→商品之间的关系图谱，支撑"用户还买过什么"、"订单关联分析"等全局查询场景。
 * 作为 OrderRagService 的补充检索能力。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class OrderGraphService {

    private static final Logger log = LoggerFactory.getLogger(OrderGraphService.class);

    // ==================== 枚举 ====================

    /** 订单关联关系类型 */
    public enum RelationType {
        /** 同一用户的其他订单 */
        SAME_USER,
        /** 同一商品的其他订单 */
        SAME_PRODUCT,
        /** 关联退款单 */
        RELATED_REFUND,
        /** 同一地址的订单 */
        SAME_ADDRESS
    }

    /** 图查询结果 */
    public static class GraphQueryResult {
        private final String orderId;
        private final String productName;
        private final Double amount;
        private final String status;
        private final RelationType relationType;
        private final double relevanceScore;

        public GraphQueryResult(String orderId, String productName, Double amount,
                                String status, RelationType relationType, double relevanceScore) {
            this.orderId = orderId;
            this.productName = productName;
            this.amount = amount;
            this.status = status;
            this.relationType = relationType;
            this.relevanceScore = relevanceScore;
        }

        public String getOrderId() { return orderId; }
        public String getProductName() { return productName; }
        public Double getAmount() { return amount; }
        public String getStatus() { return status; }
        public RelationType getRelationType() { return relationType; }
        public double getRelevanceScore() { return relevanceScore; }
    }

    private final OrderMapper orderMapper;

    public OrderGraphService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    // ==================== 核心查询 ====================

    /**
     * 查询指定用户的其他订单（同一用户关联）。
     */
    public List<GraphQueryResult> queryByUser(Long userId, String excludeOrderId, int maxResults) {
        if (userId == null) {
            return List.of();
        }
        return orderMapper.findByUserId(userId).stream()
                .filter(order -> !Objects.equals(order.getOrderId(), excludeOrderId))
                .map(order -> result(order, RelationType.SAME_USER,
                        OrderStatus.DELIVERED.matches(order.getStatus()) ? 0.9 : 0.5))
                .sorted((a, b) -> Double.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * 查询买了相同商品的其他用户（同一商品关联）。
     */
    public List<GraphQueryResult> queryByProduct(String productName, int maxResults) {
        if (productName == null || productName.isBlank()) {
            return List.of();
        }
        return orderMapper.findByProductName(productName).stream()
                .map(order -> result(order, RelationType.SAME_PRODUCT, 0.8))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * 综合查询：给定用户和商品，查找可能感兴趣的订单（"还买过什么"）。
     */
    public List<GraphQueryResult> queryRecommendations(Long userId, String currentProductName, int maxResults) {
        Map<String, GraphQueryResult> merged = new LinkedHashMap<>();

        // 1. 查同用户的其他订单
        List<OrderEntity> userOrderList = userId == null ? List.of() : orderMapper.findByUserId(userId);
        for (OrderEntity order : userOrderList) {
            if (Objects.equals(order.getProductName(), currentProductName)) continue;
            merged.put(order.getOrderId(), result(order, RelationType.SAME_USER, 0.9));
        }

        // 2. 查买过当前商品的用户还买了什么
        if (currentProductName != null && !currentProductName.isBlank()) {
            for (OrderEntity sameProduct : orderMapper.findByProductName(currentProductName)) {
                if (Objects.equals(sameProduct.getUserId(), userId)) continue;
                for (OrderEntity other : orderMapper.findByUserId(sameProduct.getUserId())) {
                    if (Objects.equals(other.getProductName(), currentProductName)) continue;
                    merged.putIfAbsent(other.getOrderId(),
                            result(other, RelationType.SAME_PRODUCT, 0.6));
                }
            }
        }

        return merged.values().stream()
                .sorted((a, b) -> Double.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    // ==================== 统计 ====================

    public List<String> queryPurchasedProductCodes(Long userId) {
        return userId == null ? List.of() : orderMapper.findPurchasedProductCodes(userId);
    }

    private static GraphQueryResult result(OrderEntity order, RelationType type, double score) {
        return new GraphQueryResult(order.getOrderId(), order.getProductName(),
                order.getAmount() == null ? null : order.getAmount().doubleValue(),
                order.getStatus(), type, score);
    }

    public int getUserCount() {
        return (int) orderMapper.findAllOrders().stream().map(OrderEntity::getUserId).distinct().count();
    }
    public int getProductCount() {
        return (int) orderMapper.findAllOrders().stream().map(OrderEntity::getProductName).distinct().count();
    }
    public int getTotalOrderCount() {
        return orderMapper.findAllOrders().size();
    }

    /**
     * 从查询文本中提取用户 ID。
     */
    public Long extractUserId(String query) {
        if (query == null) return null;
        // 简单实现：从查询中尝试提取 user-xxx 模式
        var matcher = java.util.regex.Pattern.compile("user[-:](\\d+)").matcher(query);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
