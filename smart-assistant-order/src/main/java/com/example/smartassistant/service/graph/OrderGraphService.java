package com.example.smartassistant.service.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    // ==================== 图数据 ====================

    /** 用户 → 订单列表（邻接表） */
    private final Map<Long, List<OrderNode>> userOrders = new ConcurrentHashMap<>();

    /** 商品 → 订单列表（邻接表） */
    private final Map<String, List<OrderNode>> productOrders = new ConcurrentHashMap<>();

    /** 订单内部类 */
    private record OrderNode(String orderId, String productName, Double amount,
                             String status, Long userId, String address) {}

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        log.info("[OrderGraph] 初始化订单关联图谱...");
        buildDefaultGraph();
        log.info("[OrderGraph] 初始化完成: 用户数量={}, 商品数量={}, 订单总数={}",
                userOrders.size(), productOrders.size(),
                userOrders.values().stream().mapToInt(List::size).sum());
    }

    /**
     * 构建默认订单关系数据。
     */
    private void buildDefaultGraph() {
        // 用户1: 于海阔
        addOrder(1L, "ORD-2024-001", 8999.0, "已签收", "iPhone 15 Pro", "北京市朝阳区xxx");
        addOrder(1L, "ORD-2024-002", 1999.0, "已签收", "AirPods Pro（第二代）", "北京市朝阳区xxx");
        addOrder(1L, "ORD-2024-003", 8999.0, "已发货", "MacBook Air M3", "北京市朝阳区xxx");
        addOrder(1L, "ORD-2024-005", 3999.0, "待付款", "iPad Pro M4", "北京市朝阳区xxx");

        // 用户2: 张三
        addOrder(2L, "ORD-2024-010", 6999.0, "已签收", "iPhone 15", "上海市浦东新区yyy");
        addOrder(2L, "ORD-2024-011", 1299.0, "已签收", "AirPods 4", "上海市浦东新区yyy");
        addOrder(2L, "ORD-2024-012", 2499.0, "退款中", "Apple Watch Series 9", "上海市浦东新区yyy");

        // 用户3: 李四
        addOrder(3L, "ORD-2024-020", 15999.0, "已签收", "MacBook Pro M4", "深圳市南山区zzz");
        addOrder(3L, "ORD-2024-021", 1999.0, "已取消", "AirPods Pro（第二代）", "深圳市南山区zzz");
    }

    private void addOrder(Long userId, String orderId, Double amount, String status,
                          String productName, String address) {
        OrderNode node = new OrderNode(orderId, productName, amount, status, userId, address);
        userOrders.computeIfAbsent(userId, k -> new ArrayList<>()).add(node);
        productOrders.computeIfAbsent(productName, k -> new ArrayList<>()).add(node);
    }

    // ==================== 核心查询 ====================

    /**
     * 查询指定用户的其他订单（同一用户关联）。
     */
    public List<GraphQueryResult> queryByUser(Long userId, String excludeOrderId, int maxResults) {
        if (userId == null || !userOrders.containsKey(userId)) {
            return List.of();
        }
        return userOrders.get(userId).stream()
                .filter(n -> !n.orderId().equals(excludeOrderId))
                .map(n -> new GraphQueryResult(n.orderId(), n.productName(),
                        n.amount(), n.status(), RelationType.SAME_USER,
                        "已签收".equals(n.status()) ? 0.9 : 0.5))
                .sorted((a, b) -> Double.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * 查询买了相同商品的其他用户（同一商品关联）。
     */
    public List<GraphQueryResult> queryByProduct(String productName, int maxResults) {
        if (productName == null || !productOrders.containsKey(productName)) {
            return List.of();
        }
        return productOrders.get(productName).stream()
                .map(n -> new GraphQueryResult(n.orderId(), n.productName(),
                        n.amount(), n.status(), RelationType.SAME_PRODUCT, 0.8))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * 综合查询：给定用户和商品，查找可能感兴趣的订单（"还买过什么"）。
     */
    public List<GraphQueryResult> queryRecommendations(Long userId, String currentProductName, int maxResults) {
        Map<String, GraphQueryResult> merged = new LinkedHashMap<>();

        // 1. 查同用户的其他订单
        List<OrderNode> userOrderList = userOrders.get(userId);
        if (userOrderList != null) {
            for (OrderNode node : userOrderList) {
                if (node.productName().equals(currentProductName)) continue;
                merged.put(node.orderId(), new GraphQueryResult(
                        node.orderId(), node.productName(),
                        node.amount(), node.status(),
                        RelationType.SAME_USER, 0.9
                ));
            }
        }

        // 2. 查买过当前商品的用户还买了什么
        List<OrderNode> productOrderList = productOrders.get(currentProductName);
        if (productOrderList != null) {
            for (OrderNode node : productOrderList) {
                if (node.userId().equals(userId)) continue;
                Long otherUserId = node.userId();
                List<OrderNode> otherOrders = userOrders.get(otherUserId);
                if (otherOrders != null) {
                    for (OrderNode other : otherOrders) {
                        if (other.productName().equals(currentProductName)) continue;
                        merged.putIfAbsent(other.orderId(), new GraphQueryResult(
                                other.orderId(), other.productName(),
                                other.amount(), other.status(),
                                RelationType.SAME_PRODUCT, 0.6
                        ));
                    }
                }
            }
        }

        return merged.values().stream()
                .sorted((a, b) -> Double.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    // ==================== 统计 ====================

    public int getUserCount() { return userOrders.size(); }
    public int getProductCount() { return productOrders.size(); }
    public int getTotalOrderCount() {
        return userOrders.values().stream().mapToInt(List::size).sum();
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
