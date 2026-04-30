package com.example.smartassistant.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis Session 服务（精简版）
 * 
 * <p>特点：</p>
 * <ul>
 *     <li>✅ 纯 Redis 存储，无数据库依赖</li>
 *     <li>✅ 自动过期，无需手动清理</li>
 *     <li>✅ 高性能，毫秒级响应</li>
 *     <li>✅ 支持会话验证、创建、撤销</li>
 * </ul>
 */
@Service
public class RedisSessionService {
    
    private static final Logger log = LoggerFactory.getLogger(RedisSessionService.class);
    private static final String SESSION_KEY_PREFIX = "session:";
    
    @Value("${session.ttl-days:7}")
    private long sessionTtlDays;
    
    private final StringRedisTemplate redisTemplate;

    public RedisSessionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * 创建新会话
     * 
     * @param userId 用户 ID
     * @param tokenId JWT Token ID (jti)
     * @param ipAddress 客户端 IP
     * @param userAgent 用户代理
     */
    public void createSession(Long userId, String tokenId, String ipAddress, String userAgent) {
        try {
            String redisKey = SESSION_KEY_PREFIX + tokenId;
            
            Map<String, String> sessionData = new HashMap<>();
            sessionData.put("userId", String.valueOf(userId));
            sessionData.put("tokenId", tokenId);
            sessionData.put("ipAddress", ipAddress != null ? ipAddress : "unknown");
            sessionData.put("userAgent", userAgent != null ? userAgent : "unknown");
            sessionData.put("createdAt", LocalDateTime.now().toString());
            sessionData.put("lastActiveAt", LocalDateTime.now().toString());
            sessionData.put("isActive", "true");
            
            // 存储到 Redis Hash
            redisTemplate.opsForHash().putAll(redisKey, sessionData);
            
            // 设置过期时间
            redisTemplate.expire(redisKey, sessionTtlDays, TimeUnit.DAYS);
            
            log.info("[RedisSession] 创建会话成功: userId={}, tokenId={}, TTL={}天", 
                    userId, tokenId, sessionTtlDays);
            
        } catch (Exception e) {
            log.error("[RedisSession] 创建会话失败: {}", e.getMessage(), e);
            throw new RuntimeException("创建会话失败", e);
        }
    }
    
    /**
     * 验证会话是否有效
     * 
     * @param tokenId JWT Token ID
     * @return true=有效, false=无效
     */
    public boolean validateSession(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        
        try {
            String redisKey = SESSION_KEY_PREFIX + tokenId;
            
            // 检查 Key 是否存在
            Boolean exists = redisTemplate.hasKey(redisKey);
            
            if (Boolean.TRUE.equals(exists)) {
                // 更新最后活跃时间
                updateLastActive(tokenId);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("[RedisSession] 验证会话失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 撤销会话（登出）
     * 
     * @param tokenId JWT Token ID
     */
    public void revokeSession(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return;
        }
        
        try {
            String redisKey = SESSION_KEY_PREFIX + tokenId;
            
            // 方式1: 直接删除（推荐，立即失效）
            redisTemplate.delete(redisKey);
            
            // 方式2: 标记为无效（保留审计日志）
            // redisTemplate.opsForHash().put(redisKey, "isActive", "false");
            // redisTemplate.opsForHash().put(redisKey, "revokedAt", LocalDateTime.now().toString());
            
            log.info("[RedisSession] 会话已撤销: tokenId={}", tokenId);
            
        } catch (Exception e) {
            log.error("[RedisSession] 撤销会话失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取会话信息
     * 
     * @param tokenId JWT Token ID
     * @return 会话数据 Map，不存在返回 null
     */
    public Map<Object, Object> getSessionInfo(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return null;
        }
        
        try {
            String redisKey = SESSION_KEY_PREFIX + tokenId;
            return redisTemplate.opsForHash().entries(redisKey);
            
        } catch (Exception e) {
            log.error("[RedisSession] 获取会话信息失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取用户的活跃会话数量
     * 
     * @param userId 用户 ID
     * @return 活跃会话数
     */
    public int getActiveSessionCount(Long userId) {
        try {
            // 注意：这需要扫描所有 session key，性能较低
            // 生产环境建议使用 Set 维护用户会话列表
            return 0; // 暂时返回 0
            
        } catch (Exception e) {
            log.error("[RedisSession] 获取会话数量失败: {}", e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 更新最后活跃时间
     */
    private void updateLastActive(String tokenId) {
        try {
            String redisKey = SESSION_KEY_PREFIX + tokenId;
            redisTemplate.opsForHash().put(redisKey, "lastActiveAt", LocalDateTime.now().toString());
            
            // 刷新过期时间
            redisTemplate.expire(redisKey, sessionTtlDays, TimeUnit.DAYS);
            
        } catch (Exception e) {
            log.debug("[RedisSession] 更新活跃时间失败: {}", e.getMessage());
        }
    }
}
