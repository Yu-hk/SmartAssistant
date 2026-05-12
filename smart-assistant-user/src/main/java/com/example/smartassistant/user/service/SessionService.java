/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user.service;

import com.example.smartassistant.user.mapper.UserSessionMapper;
import com.example.smartassistant.user.model.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理服务
 * 结合 Redis（快速验证）和数据库（持久化审计）
 */
@Service
public class SessionService {
    
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final String SESSION_KEY_PREFIX = "session:";
    
    @Value("${session.enabled:true}")
    private boolean sessionEnabled;
    
    @Value("${session.ttl-days:7}")
    private long sessionTtlDays;
    
    @Value("${session.db-storage-enabled:true}")
    private boolean dbStorageEnabled;  // ⭐ 是否启用数据库存储
    
    private final UserSessionMapper sessionMapper;
    private final StringRedisTemplate redisTemplate;

    public SessionService(UserSessionMapper sessionMapper,
                         StringRedisTemplate redisTemplate) {
        this.sessionMapper = sessionMapper;
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * 创建新会话
     * ⭐ 优化：数据库写入异步化，提升响应速度
     */
    public void createSession(Long userId, String tokenId, String ipAddress, String userAgent) {
        try {
            // 1. 先存储到 Redis（快速路径，同步执行）
            saveSessionToRedis(userId, tokenId, ipAddress);
            
            // 2. 异步保存到数据库（不阻塞响应）
            if (dbStorageEnabled) {
                saveSessionToDatabaseAsync(userId, tokenId, ipAddress, userAgent);
            }
            
            log.info("[Session] 创建会话成功: userId={}, tokenId={}", userId, tokenId);
            
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            // ⭐ 优雅降级: 如果表不存在,只使用 Redis
            if (errorMsg != null && errorMsg.contains("关系 \"user_sessions\" 不存在")) {
                log.warn("[Session] ⚠️ user_sessions 表不存在,仅使用 Redis 存储会话 (可执行 SQL 创建表)");
                try {
                    saveSessionToRedis(userId, tokenId, ipAddress);
                } catch (Exception redisEx) {
                    log.error("[Session] Redis 存储也失败: {}", redisEx.getMessage());
                    throw new RuntimeException("创建会话失败", redisEx);
                }
            } else {
                log.error("[Session] 创建会话失败: {}", e.getMessage(), e);
                throw new RuntimeException("创建会话失败", e);
            }
        }
    }
    
    /**
     * ⭐ 异步保存会话到数据库
     */
    @Async("taskExecutor")
    public void saveSessionToDatabaseAsync(Long userId, String tokenId, String ipAddress, String userAgent) {
        try {
            UserSession session = new UserSession();
            session.setUserId(userId);
            session.setTokenId(tokenId);
            session.setIpAddress(ipAddress);
            session.setUserAgent(userAgent);
            session.setExpiresAt(LocalDateTime.now().plusDays(sessionTtlDays));
            session.setIsActive(true);
            session.setIsRevoked(false);
            session.setLastActiveAt(LocalDateTime.now());
            session.setCreatedAt(LocalDateTime.now());
            
            // 提取设备信息
            Map<String, String> deviceInfo = extractDeviceInfo(userAgent);
            session.setDeviceInfo(deviceInfo);
            
            sessionMapper.insert(session);
            log.debug("[Session] 会话已异步保存到数据库: sessionId={}", session.getId());
            
        } catch (Exception e) {
            log.error("[Session] 异步保存会话到数据库失败: {}", e.getMessage(), e);
            // 不抛出异常，避免影响主流程
        }
    }
    
    /**
     * 保存会话到 Redis
     */
    private void saveSessionToRedis(Long userId, String tokenId, String ipAddress) {
        String redisKey = SESSION_KEY_PREFIX + tokenId;
        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("userId", userId);
        sessionData.put("tokenId", tokenId);
        sessionData.put("ipAddress", ipAddress);
        sessionData.put("createdAt", LocalDateTime.now().toString());
        sessionData.put("lastActiveAt", LocalDateTime.now().toString());
        
        redisTemplate.opsForHash().putAll(redisKey, 
            sessionData.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                    e -> e.getValue().toString()
                ))
        );
        redisTemplate.expire(redisKey, sessionTtlDays, TimeUnit.DAYS);
    }
    
    /**
     * 验证会话是否有效
     */
    public boolean validateSession(String tokenId) {
        // 如果 Session 功能被禁用，直接返回 true（仅验证 JWT）
        if (!sessionEnabled) {
            log.debug("[Session] Session 验证已禁用，跳过");
            return true;
        }
        
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        
        try {
            // 1. 先查 Redis（快速路径）
            String redisKey = SESSION_KEY_PREFIX + tokenId;
            Boolean exists = redisTemplate.hasKey(redisKey);
            
            if (exists) {
                // Redis 中存在，更新最后活跃时间
                updateLastActive(tokenId);
                return true;
            }
            
            // 2. Redis 中不存在，查数据库（降级路径）
            try {
                UserSession session = sessionMapper.findByTokenId(tokenId);
                
                if (session != null) {
                    
                    // 检查会话状态
                    if (!session.getIsActive() || session.getIsRevoked()) {
                        return false;
                    }
                    
                    // 检查是否过期
                    if (session.getExpiresAt() != null && 
                        LocalDateTime.now().isAfter(session.getExpiresAt())) {
                        return false;
                    }
                    
                    // 会话有效，重新写入 Redis
                    restoreSessionToRedis(session);
                    return true;
                }
            } catch (Exception dbEx) {
                // ⭐ 优雅降级: 如果表不存在,只依赖 Redis
                String errorMsg = dbEx.getMessage();
                if (errorMsg != null && errorMsg.contains("关系 \"user_sessions\" 不存在")) {
                    log.debug("[Session] user_sessions 表不存在,跳过数据库验证");
                } else {
                    log.warn("[Session] 数据库验证失败: {}", dbEx.getMessage());
                }
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("[Session] 验证会话失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 撤销会话（登出）
     */
    @Transactional
    public void revokeSession(String tokenId) {
        try {
            // 1. 从 Redis 删除
            String redisKey = SESSION_KEY_PREFIX + tokenId;
            redisTemplate.delete(redisKey);
            
            // 2. 更新数据库
            UserSession session = sessionMapper.findByTokenId(tokenId);
            if (session != null) {
                session.setIsActive(false);
                session.setIsRevoked(true);
                session.setRevokedAt(LocalDateTime.now());
                sessionMapper.updateById(session);
                
                log.info("[Session] 会话已撤销: tokenId={}", tokenId);
            }
            
        } catch (Exception e) {
            log.error("[Session] 撤销会话失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 撤销用户的所有会话（强制所有设备登出）
     */
    @Transactional
    public void revokeAllSessions(Long userId) {
        try {
            List<UserSession> sessions = sessionMapper.findByUserIdAndIsActiveTrue(userId);
            
            for (UserSession session : sessions) {
                // 从 Redis 删除
                String redisKey = SESSION_KEY_PREFIX + session.getTokenId();
                redisTemplate.delete(redisKey);
                
                // 更新数据库
                session.setIsActive(false);
                session.setIsRevoked(true);
                session.setRevokedAt(LocalDateTime.now());
                sessionMapper.updateById(session);
            }
            
            log.info("[Session] 用户 {} 的所有会话已撤销，共 {} 个", userId, sessions.size());
            
        } catch (Exception e) {
            log.error("[Session] 撤销所有会话失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户的活跃会话列表
     */
    public List<Map<String, Object>> getActiveSessions(Long userId) {
        List<UserSession> sessions = sessionMapper.findByUserIdAndIsActiveTrue(userId);
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserSession session : sessions) {
            Map<String, Object> sessionInfo = new HashMap<>();
            sessionInfo.put("id", session.getId());
            sessionInfo.put("tokenId", session.getTokenId());
            sessionInfo.put("ipAddress", session.getIpAddress());
            sessionInfo.put("userAgent", session.getUserAgent());
            sessionInfo.put("createdAt", session.getCreatedAt());
            sessionInfo.put("lastActiveAt", session.getLastActiveAt());
            sessionInfo.put("deviceInfo", session.getDeviceInfo());
            result.add(sessionInfo);
        }
        
        return result;
    }
    
    /**
     * 更新最后活跃时间
     */
    private void updateLastActive(String tokenId) {
        try {
            String redisKey = SESSION_KEY_PREFIX + tokenId;
            redisTemplate.opsForHash().put(redisKey, "lastActiveAt", 
                LocalDateTime.now().toString());
            
            // 续期
            redisTemplate.expire(redisKey, sessionTtlDays, TimeUnit.DAYS);
            
        } catch (Exception e) {
            log.warn("[Session] 更新活跃时间失败: {}", e.getMessage());
        }
    }
    
    /**
     * 从数据库恢复会话到 Redis
     */
    private void restoreSessionToRedis(UserSession session) {
        try {
            String redisKey = SESSION_KEY_PREFIX + session.getTokenId();
            Map<String, Object> sessionData = new HashMap<>();
            sessionData.put("userId", session.getUserId());
            sessionData.put("tokenId", session.getTokenId());
            sessionData.put("ipAddress", session.getIpAddress());
            sessionData.put("createdAt", session.getCreatedAt().toString());
            sessionData.put("lastActiveAt", LocalDateTime.now().toString());
            
            redisTemplate.opsForHash().putAll(redisKey,
                sessionData.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                        e -> e.getValue().toString()
                    ))
            );
            
            long ttl = Duration.between(
                LocalDateTime.now(), session.getExpiresAt()
            ).toDays();
            
            if (ttl > 0) {
                redisTemplate.expire(redisKey, ttl, TimeUnit.DAYS);
            }
            
        } catch (Exception e) {
            log.warn("[Session] 恢复会话到 Redis 失败: {}", e.getMessage());
        }
    }
    
    /**
     * 提取设备信息
     */
    private Map<String, String> extractDeviceInfo(String userAgent) {
        Map<String, String> info = new HashMap<>();
        
        if (userAgent == null) {
            info.put("device", "Unknown");
            info.put("os", "Unknown");
            info.put("browser", "Unknown");
            return info;
        }
        
        // 简单解析（生产环境可使用 ua-parser 库）
        if (userAgent.contains("Mobile")) {
            info.put("device", "Mobile");
        } else if (userAgent.contains("Tablet")) {
            info.put("device", "Tablet");
        } else {
            info.put("device", "Desktop");
        }
        
        if (userAgent.contains("Windows")) {
            info.put("os", "Windows");
        } else if (userAgent.contains("Mac")) {
            info.put("os", "macOS");
        } else if (userAgent.contains("Linux")) {
            info.put("os", "Linux");
        } else if (userAgent.contains("Android")) {
            info.put("os", "Android");
        } else if (userAgent.contains("iOS") || userAgent.contains("iPhone")) {
            info.put("os", "iOS");
        } else {
            info.put("os", "Unknown");
        }
        
        if (userAgent.contains("Chrome")) {
            info.put("browser", "Chrome");
        } else if (userAgent.contains("Firefox")) {
            info.put("browser", "Firefox");
        } else if (userAgent.contains("Safari")) {
            info.put("browser", "Safari");
        } else if (userAgent.contains("Edge")) {
            info.put("browser", "Edge");
        } else {
            info.put("browser", "Unknown");
        }
        
        return info;
    }
}
