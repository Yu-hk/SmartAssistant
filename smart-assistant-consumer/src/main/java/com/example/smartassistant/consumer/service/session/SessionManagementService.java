package com.example.smartassistant.consumer.service.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理服务
 * 为每个用户管理唯一的 threadId，支持会话隔离和清理
 */
@Service
public class SessionManagementService {
    
    private static final Logger log = LoggerFactory.getLogger(SessionManagementService.class);
    
    // 用户会话映射：userId -> threadId
    private final Map<String, String> userSessions = new ConcurrentHashMap<>();
    
    // 会话最后访问时间：threadId -> timestamp
    private final Map<String, Long> sessionLastAccess = new ConcurrentHashMap<>();
    
    // ⭐ 会话超时时间（毫秒）：从配置文件读取，默认 30 分钟
    @Value("${session.timeout-ms:1800000}")
    private long sessionTimeoutMs;
    
    /**
     * 获取或创建用户的 threadId
     * 如果用户已有活跃会话，返回现有 threadId
     * 如果会话已过期或不存在，创建新的 threadId
     *
     * @param userId 用户 ID（可以从请求头、Cookie 或 JWT 中获取）
     * @return 唯一的 threadId
     */
    public String getOrCreateThreadId(String userId) {
        if (userId == null || userId.isEmpty()) {
            // 匿名用户：每次生成新的 threadId
            String newThreadId = generateNewThreadId();
            log.debug("[Session] 匿名用户，生成新 threadId: {}", newThreadId);
            return newThreadId;
        }
        
        String existingThreadId = userSessions.get(userId);
        
        // 检查现有会话是否有效
        if (existingThreadId != null && isSessionValid(existingThreadId)) {
            // 更新最后访问时间
            sessionLastAccess.put(existingThreadId, System.currentTimeMillis());
            log.debug("[Session] 复用现有会话: userId={}, threadId={}", userId, existingThreadId);
            return existingThreadId;
        }
        
        // 创建新会话
        String newThreadId = generateNewThreadId();
        userSessions.put(userId, newThreadId);
        sessionLastAccess.put(newThreadId, System.currentTimeMillis());
        
        // 清理旧会话
        if (existingThreadId != null) {
            sessionLastAccess.remove(existingThreadId);
            log.info("[Session] 用户会话已更新: userId={}, oldThreadId={}, newThreadId={}", 
                    userId, existingThreadId, newThreadId);
        } else {
            log.info("[Session] 创建新会话: userId={}, threadId={}", userId, newThreadId);
        }
        
        return newThreadId;
    }
    
    /**
     * 强制刷新用户会话（生成新的 threadId）
     * 适用于用户明确要求"重新开始"或切换场景
     *
     * @param userId 用户 ID
     * @return 新的 threadId
     */
    public String refreshSession(String userId) {
        if (userId == null || userId.isEmpty()) {
            return generateNewThreadId();
        }
        
        String oldThreadId = userSessions.remove(userId);
        if (oldThreadId != null) {
            sessionLastAccess.remove(oldThreadId);
            log.info("[Session] 强制刷新会话: userId={}, oldThreadId={}", userId, oldThreadId);
        }
        
        String newThreadId = generateNewThreadId();
        userSessions.put(userId, newThreadId);
        sessionLastAccess.put(newThreadId, System.currentTimeMillis());
        
        return newThreadId;
    }
    
    /**
     * 清理过期会话（自动定时调度，每 60 秒执行一次）
     * 建议定期调用（如每分钟一次）
     */
    @Scheduled(fixedDelay = 60000)
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int cleanedCount = 0;
        
        for (Map.Entry<String, Long> entry : sessionLastAccess.entrySet()) {
            String threadId = entry.getKey();
            long lastAccess = entry.getValue();
            
            if (now - lastAccess > sessionTimeoutMs) {
                sessionLastAccess.remove(threadId);
                
                // 同时清理 userSessions 中的反向映射
                userSessions.entrySet().removeIf(e -> e.getValue().equals(threadId));
                
                cleanedCount++;
                log.debug("[Session] 清理过期会话: threadId={}, 最后访问: {}分钟前", 
                        threadId, (now - lastAccess) / 60000);
            }
        }
        
        if (cleanedCount > 0) {
            log.info("[Session] 会话清理完成: 清理 {} 个过期会话, 剩余 {} 个活跃会话", 
                    cleanedCount, userSessions.size());
        }
    }
    
    /**
     * 获取当前活跃会话数
     */
    public int getActiveSessionCount() {
        return userSessions.size();
    }
    
    /**
     * 检查会话是否有效（未过期）
     */
    private boolean isSessionValid(String threadId) {
        Long lastAccess = sessionLastAccess.get(threadId);
        if (lastAccess == null) {
            return false;
        }
        
        long now = System.currentTimeMillis();
        return (now - lastAccess) <= sessionTimeoutMs;
    }
    
    /**
     * 生成新的 threadId
     * 格式：session_{timestamp}_{random}
     */
    private String generateNewThreadId() {
        long timestamp = System.currentTimeMillis();
        long random = (long)(Math.random() * 10000);
        return String.format("session_%d_%d", timestamp, random);
    }
}
