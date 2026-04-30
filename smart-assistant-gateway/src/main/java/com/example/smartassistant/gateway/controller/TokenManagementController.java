package com.example.smartassistant.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Token 管理控制器
 * 提供 Token 黑名单相关接口
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
     * ⭐ 将 Token 加入黑名单（登出）
     * POST /api/gateway/token/blacklist
     * Body: {"tokenId": "uuid-xxx-xxx"}
     * 
     * @param requestBody 包含 tokenId 的请求体
     * @return 操作结果
     */
    @PostMapping("/blacklist")
    public Mono<ResponseEntity<Map<String, Object>>> addToBlacklist(@RequestBody Map<String, String> requestBody) {
        return Mono.fromCallable(() -> {
            String tokenId = requestBody.get("tokenId");
            
            if (tokenId == null || tokenId.isEmpty()) {
                log.warn("[Token Blacklist] tokenId 为空");
                return ResponseEntity.badRequest()
                    .body(Map.of(
                        "success", false,
                        "message", "tokenId is required"
                    ));
            }
            
            // 将 Token ID 加入 Redis 黑名单
            String blacklistKey = "blacklist:" + tokenId;
            
            // 设置过期时间为 Token 的剩余有效期（这里简化为 24 小时）
            redisTemplate.opsForValue().set(blacklistKey, "revoked", Duration.ofHours(24)).block();
            
            log.info("[Token Blacklist] Token 已加入黑名单: tokenId={}", tokenId);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Token has been revoked"
            ));
        });
    }
    
    /**
     * 检查 Token 是否在黑名单中
     * GET /api/gateway/token/check?tokenId=xxx
     */
    @GetMapping("/check")
    public Mono<ResponseEntity<Map<String, Object>>> checkBlacklist(@RequestParam String tokenId) {
        return Mono.fromCallable(() -> {
            String blacklistKey = "blacklist:" + tokenId;
            Boolean exists = redisTemplate.hasKey(blacklistKey).block(); // Controller 中可以 block
            
            boolean isBlacklisted = Boolean.TRUE.equals(exists);
            
            return ResponseEntity.ok(Map.of(
                "tokenId", tokenId,
                "isBlacklisted", isBlacklisted
            ));
        });
    }
}
