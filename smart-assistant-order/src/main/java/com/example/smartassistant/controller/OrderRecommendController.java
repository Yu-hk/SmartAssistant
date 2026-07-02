package com.example.smartassistant.controller;

import com.example.smartassistant.service.graph.OrderGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        var orders = orderGraphService.queryByUser(userId, null, 20);
        return orders.stream()
                .map(o -> {
                    // 尝试从商品名称推断编码
                    String name = o.getProductName();
                    if (name != null) {
                        if (name.contains("iPhone 15 Pro")) return "IPHONE-15-PRO";
                        if (name.contains("iPhone")) return "IPHONE-16-PRO";
                        if (name.contains("AirPods Pro")) return "AIRPODS-PRO";
                        if (name.contains("MacBook Air")) return "MACBOOK-AIR-M3";
                        if (name.contains("MacBook Pro")) return "MACBOOK-PRO-M4";
                        if (name.contains("iPad Pro")) return "IPAD-PRO-M4";
                        if (name.contains("Apple Watch")) return "APPLE-WATCH-U2";
                    }
                    return name;
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
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
