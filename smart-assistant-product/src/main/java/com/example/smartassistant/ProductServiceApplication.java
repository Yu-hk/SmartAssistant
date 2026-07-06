/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant;

import com.example.smartassistant.common.interceptor.EnableServiceInterceptor;
import com.example.smartassistant.spi.InMemoryProductBackend;
import com.example.smartassistant.spi.ProductBackend;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Product Service 启动类
 * 启动后，商品咨询智能体将自动注册到 Nacos
 */
@Slf4j
@SpringBootApplication
@EnableServiceInterceptor(
        basePackages = {
                "com.example.smartassistant.controller",
                "com.example.smartassistant.service",
                "com.example.smartassistant.mapper"
        },
        slowThresholdMs = 1000
)
@EnableDiscoveryClient
@ComponentScan({
        "com.example.smartassistant",
        "com.example.smartassistant.common"
})
public class ProductServiceApplication {

    @Bean
    public ProductBackend inMemoryProductBackend() {
        return new InMemoryProductBackend();
    }

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
        log.info("==================================================");
        log.info("  Product Service (商品咨询 Agent) 启动成功!");
        log.info("  端口：8084");
        log.info("==================================================");
    }
}
