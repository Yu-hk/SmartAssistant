package com.example.smartassistant.controller;

import com.example.smartassistant.service.graph.OrderGraphService;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * P3 订单推荐 REST API（供推荐服务调用）。
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@RestController
@RequestMapping("/api/order")
public class OrderRecommendController {

    private final OrderGraphService orderGraphService;

    public OrderRecommendController(OrderGraphService orderGraphService) {
        this.orderGraphService = orderGraphService;
    }

    /**
     * 获取指定用户的购买记录（商品编码列表）。
     */
    @GetMapping("/user/{userId}/products")
    public List<String> getUserPurchasedProducts(@PathVariable("userId") Long userId) {
        return orderGraphService.queryPurchasedProductCodes(userId);
    }

    /**
     * 获取指定用户的订单摘要。
     */
    @GetMapping("/user/{userId}/orders")
    public List<Map<String, Object>> getUserOrders(@PathVariable("userId") Long userId) {
        var orders = orderGraphService.queryByUser(userId, null, 20);
        return orders.stream().map(o -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderId", o.getOrderId());
            item.put("productName", o.getProductName());
            item.put("amount", o.getAmount());
            item.put("status", o.getStatus());
            return item;
        }).collect(Collectors.toList());
    }
}
