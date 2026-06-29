package com.example.smartassistant.recommend.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;

/**
 * Feign 客户端 — 调用 Order 模块的 REST API。
 * <p>
 * 通过 Nacos 服务发现调用 smart-assistant-order。
 * </p>
 */
@FeignClient(name = "smart-assistant-order", path = "/api/order")
public interface OrderFeignClient {

    /**
     * 获取指定用户的购买记录（商品编码列表）。
     */
    @GetMapping("/user/{userId}/products")
    List<String> getUserPurchasedProducts(@PathVariable("userId") Long userId);

    /**
     * 获取指定用户的订单摘要。
     */
    @GetMapping("/user/{userId}/orders")
    List<Map<String, Object>> getUserOrders(@PathVariable("userId") Long userId);
}
