package com.example.smartassistant.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;  // ⭐ 启用定时任务

/**
 * Router Service 启动类
 * 智能路由服务 - 负责意图识别和 Agent 调度
 */
@SpringBootApplication
@EnableDiscoveryClient  // 启用 Nacos 服务发现
@EnableScheduling       // ⭐ 启用定时任务调度（用于健康检查）
@ComponentScan({
        "com.example.smartassistant.router",     // 主模块
        "com.example.smartassistant.common"      // ⭐ 公共模块（ChineseTokenizer 等）
})
public class RouterApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(RouterApplication.class, args);
        System.out.println("==================================================");
        System.out.println("  Router Service 启动成功!");
        System.out.println("  端口: 8083");
        System.out.println("  API: POST /api/router/route - 智能路由");
        System.out.println("==================================================");
    }
}
