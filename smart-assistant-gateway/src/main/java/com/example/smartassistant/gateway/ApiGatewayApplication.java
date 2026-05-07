package com.example.smartassistant.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API Gateway 启动类
 * 统一入口、认证、限流、路由
 */
@Slf4j
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
        log.info("==================================================");
        log.info("  API Gateway 启动成功!");
        log.info("  端口: 8081");
        log.info("==================================================");
    }
}
