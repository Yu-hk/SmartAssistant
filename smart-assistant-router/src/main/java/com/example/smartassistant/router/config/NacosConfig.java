/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Nacos 配置类 - 手动创建 NamingService Bean
 */
@Configuration
public class NacosConfig {
    
    @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
    private String serverAddr;
    
    @Value("${spring.ai.alibaba.a2a.nacos.discovery.namespace:}")
    private String namespace;
    
    @Value("${spring.ai.alibaba.a2a.nacos.discovery.username:${NACOS_USERNAME:nacos}}")
    private String username;
    
    @Value("${spring.ai.alibaba.a2a.nacos.discovery.password:${NACOS_PASSWORD:nacos123}}")
    private String password;
    
    /**
     * 创建 Nacos NamingService Bean
     */
    @Bean
    public NamingService namingService() throws Exception {
        // 构建 Properties
        java.util.Properties properties = new java.util.Properties();
        properties.put("serverAddr", serverAddr);
        properties.put("namespace", namespace);
        properties.put("username", username);
        properties.put("password", password);
        
        return NacosFactory.createNamingService(properties);
    }
}
