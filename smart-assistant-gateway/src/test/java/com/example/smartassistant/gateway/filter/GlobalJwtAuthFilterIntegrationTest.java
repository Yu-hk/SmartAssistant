/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Gateway 认证过滤器集成测试
 * 验证 JWT 认证流程的正确性
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.cloud.gateway.discovery.locator.enabled=false",
                "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false",
                "management.health.redis.enabled=false"
        })
@AutoConfigureWebTestClient
class GlobalJwtAuthFilterIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUpRedis() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));
    }

    @Test
    @DisplayName("白名单路径无需认证：健康检查接口")
    void testHealthEndpointPublic() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("无 Token 访问受保护路径返回 401")
    void testProtectedEndpointWithoutToken() {
        webTestClient.get()
                .uri("/api/math/chat")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("无效 Token 返回 401")
    void testInvalidToken() {
        webTestClient.get()
                .uri("/api/math/chat")
                .header("Authorization", "Bearer invalid-token-here")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("POST 健康检查接口可用")
    void testPostHealthEndpoint() {
        webTestClient.post()
                .uri("/actuator/health")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(405);
    }
}
