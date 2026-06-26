package com.example.smartassistant.gateway.controller;

import com.example.smartassistant.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Token 管理控制器
 * <p>
 * 提供 Token 黑名单相关接口。
 * 统一返回 {@link ApiResponse} 格式。
 */
@RestController
@RequestMapping("/api/gateway/token")
@Slf4j
public class TokenManagementController {

    private final ReactiveStringRedisTemplate redisTemplate;

    public TokenManagementController(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将 Token 加入黑名单（登出）
     * POST /api/gateway/token/blacklist
     */
    @PostMapping("/blacklist")
    public Mono<ApiResponse<Map<String, Object>>> addToBlacklist(@RequestBody Map<String, String> requestBody) {
        return Mono.fromCallable(() -> {
            String tokenId = requestBody.get("tokenId");

            if (tokenId == null || tokenId.isEmpty()) {
                log.warn("[Token Blacklist] tokenId 为空");
                return ApiResponse.error(400, "tokenId is required");
            }

            String blacklistKey = "blacklist:" + tokenId;
            redisTemplate.opsForValue().set(blacklistKey, "revoked", Duration.ofHours(24)).block();

            log.info("[Token Blacklist] Token 已加入黑名单: tokenId={}", tokenId);

            Map<String, Object> data = new HashMap<>();
            data.put("tokenId", tokenId);
            data.put("revoked", true);
            return ApiResponse.success(data);
        });
    }

    /**
     * 检查 Token 是否在黑名单中
     * GET /api/gateway/token/check?tokenId=xxx
     */
    @GetMapping("/check")
    public Mono<ApiResponse<Map<String, Object>>> checkBlacklist(@RequestParam String tokenId) {
        return Mono.fromCallable(() -> {
            String blacklistKey = "blacklist:" + tokenId;
            Boolean exists = redisTemplate.hasKey(blacklistKey).block();

            boolean isBlacklisted = Boolean.TRUE.equals(exists);

            Map<String, Object> data = new HashMap<>();
            data.put("tokenId", tokenId);
            data.put("isBlacklisted", isBlacklisted);
            return ApiResponse.success(data);
        });
    }
}
