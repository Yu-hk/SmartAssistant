/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router;

import com.example.smartassistant.common.interceptor.EnableServiceInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;  // ⭐ 启用定时任务

/**
 * Router Service 启动类
 * 智能路由服务 - 负责意图识别和 Agent 调度
 */
@Slf4j
@SpringBootApplication
@EnableServiceInterceptor(
        basePackages = {
                "com.example.smartassistant.router.controller",
                "com.example.smartassistant.router.service",
                "com.example.smartassistant.router.mapper"
        },
        slowThresholdMs = 1000
)
@EnableDiscoveryClient  // 启用 Nacos 服务发现
@EnableScheduling       // ⭐ 启用定时任务调度（用于健康检查）
@ComponentScan({
        "com.example.smartassistant.router",     // 主模块
        "com.example.smartassistant.common"      // ⭐ 公共模块（ChineseTokenizer 等）
})
public class RouterApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(RouterApplication.class, args);
        log.info("==================================================");
        log.info("  Router Service 启动成功!");
        log.info("  端口: 8083");
        log.info("  API: POST /api/router/route - 智能路由");
        log.info("==================================================");
    }
}
