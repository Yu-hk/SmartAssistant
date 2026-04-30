package com.example.smartassistant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Travel Service 启动类 (MyBatis Plus)
 * 启动后，位置与出行规划智能体将自动注册到 Nacos
 */
@SpringBootApplication
@MapperScan("com.example.smartassistant.mapper")
@EnableDiscoveryClient  // ⭐ 启用 Nacos 服务发现
public class TravelServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TravelServiceApplication.class, args);
        System.out.println("==================================================");
        System.out.println("  Travel Service (位置与出行规划 Agent) 启动成功!");
        System.out.println("  端口：8083");
        System.out.println("  Nacos 注册：已启用 (需要 Nacos 3.x 版本)");
        System.out.println("==================================================");
    }
}
