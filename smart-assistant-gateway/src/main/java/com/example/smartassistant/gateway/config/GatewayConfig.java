package com.example.smartassistant.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Gateway 配置类
 * 
 * <p>路由配置已迁移到 application.yml，此类仅用于扩展配置</p>
 */
@Configuration
@Slf4j
public class GatewayConfig {
    
    // ⭐ 所有路由配置已在 application.yml 中定义
    // 包括：Consumer、Router、Travel、Food Service
    // JWT 认证过滤器已配置为全局默认过滤器
}
