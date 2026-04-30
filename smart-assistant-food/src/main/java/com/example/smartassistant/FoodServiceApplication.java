package com.example.smartassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Food Service 启动类
 * 启动后，美食推荐智能体将自动注册到 Nacos
 */
@SpringBootApplication
@EnableDiscoveryClient  // ⭐ 启用 Nacos 服务发现
public class FoodServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FoodServiceApplication.class, args);
        System.out.println("==================================================");
        System.out.println("  Food Service (美食推荐 Agent) 启动成功!");
        System.out.println("  端口：8084");
        System.out.println("  Nacos 注册：已启用 (需要 Nacos 3.x 版本)");
        System.out.println("  Agent 名称：food_recommendation_agent");
        System.out.println("==================================================");
    }
}
