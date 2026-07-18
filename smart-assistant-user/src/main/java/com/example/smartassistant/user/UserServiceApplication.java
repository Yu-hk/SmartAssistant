/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user;

import com.example.smartassistant.common.exception.GlobalExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

import org.springframework.context.annotation.Import;

@Slf4j
@SpringBootApplication
@EnableDiscoveryClient
@Import(GlobalExceptionHandler.class)
@MapperScan("com.example.smartassistant.user.mapper")
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
        log.info("===========================================");
        log.info("  User Service Started Successfully!");
        log.info("  Port: 8085");
        log.info("  Nacos: http://localhost:8848");
        log.info("===========================================");
    }
}
