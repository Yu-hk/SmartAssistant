/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Food Service 启动类
 * 启动后，美食推荐智能体将自动注册到 Nacos
 */
@Slf4j
@SpringBootApplication(exclude = {
    DashScopeChatAutoConfiguration.class,   // Chat 已切换至 DeepSeek
    DashScopeAgentAutoConfiguration.class   // Embedding 已切换至 BGE
})
@EnableDiscoveryClient  // ⭐ 启用 Nacos 服务发现
public class FoodServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FoodServiceApplication.class, args);
        log.info("==================================================");
        log.info("  Food Service (美食推荐 Agent) 启动成功!");
        log.info("  端口：8084");
        log.info("==================================================");
    }
}
