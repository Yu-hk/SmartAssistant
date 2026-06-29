package com.example.smartassistant.recommend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * P3 推荐服务 - 跨模块商品推荐
 * <p>
 * 聚合 Product 模块的商品图谱和 Order 模块的用户购买历史，
 * 通过协同过滤 + 图谱关系提供跨模块推荐能力。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class RecommendServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecommendServiceApplication.class, args);
    }
}
