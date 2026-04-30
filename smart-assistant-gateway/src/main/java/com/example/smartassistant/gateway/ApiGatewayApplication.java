package com.example.smartassistant.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API Gateway 启动类
 * 统一入口、认证、限流、路由
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
        System.out.println("==================================================");
        System.out.println("  API Gateway 启动成功!");
        System.out.println("  端口: 8080");
        System.out.println("  路由规则:");
        System.out.println("    - /api/consumer/** → consumer-service");
        System.out.println("    - /api/router/**   → router-service");
        System.out.println("    - /api/travel/**   → travel-service");
        System.out.println("    - /api/food/**     → food-service");
        System.out.println("  功能:");
        System.out.println("    ✅ JWT 认证");
        System.out.println("    ✅ Redis 限流");
        System.out.println("    ✅ 负载均衡");
        System.out.println("    ✅ 服务发现");
        System.out.println("==================================================");
    }
}
