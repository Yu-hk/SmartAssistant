/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Order Service 启动类
 * 启动后，订单客服智能体将自动注册到 Nacos
 */
@Slf4j
@SpringBootApplication
@MapperScan("com.example.smartassistant.mapper")
@EnableDiscoveryClient
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
        log.info("==================================================");
        log.info("  Order Service (订单客服 Agent) 启动成功!");
        log.info("  端口：8085");
        log.info("  Nacos 注册：已启用");
        log.info("==================================================");
    }
}
