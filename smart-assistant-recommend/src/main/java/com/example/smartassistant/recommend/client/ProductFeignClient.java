package com.example.smartassistant.recommend.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;

/**
 * Feign 客户端 — 调用 Product 模块的 REST API。
 * <p>
 * 通过 Nacos 服务发现调用 smart-assistant-product。
 * </p>
 */
@FeignClient(name = "smart-assistant-product", path = "/api/product",
             fallbackFactory = ProductFeignClientFallbackFactory.class)
public interface ProductFeignClient {

    /**
     * 获取所有商品列表。
     */
    @GetMapping("/list")
    List<Map<String, String>> getAllProducts();

    /**
     * 获取指定商品的关联推荐（基于 ProductGraphService）。
     */
    @GetMapping("/{code}/recommend")
    List<Map<String, Object>> getProductRecommendations(@PathVariable("code") String code);

    /**
     * 获取商品详情。
     */
    @GetMapping("/{code}/info")
    String getProductInfo(@PathVariable("code") String code);
}
