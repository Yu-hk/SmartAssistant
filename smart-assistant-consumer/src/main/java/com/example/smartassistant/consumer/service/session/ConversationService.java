package com.example.smartassistant.consumer.service.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话管理服务 - 维护对话状态，避免重复建议
 */
@Service
public class ConversationService {
    
    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final String CONTEXT_KEY = "conversation:context:";
    private static final String HISTORY_KEY = "conversation:history:";
    private static final Duration TTL = Duration.ofMinutes(30); // 30分钟过期
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final Map<String, ConversationContext> localCache = new ConcurrentHashMap<>();
    
    public ConversationService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * 获取或创建对话上下文
     */
    public ConversationContext getOrCreateContext(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = generateSessionId();
        }
        
        try {
            String key = CONTEXT_KEY + sessionId;
            ConversationContext context = (ConversationContext) redisTemplate.opsForValue().get(key);
            
            if (context == null) {
                context = new ConversationContext(sessionId);
                saveContext(context);
            }
            
            return context;
            
        } catch (Exception e) {
            log.warn("[Conversation] Redis 访问失败，使用本地缓存: {}", e.getMessage());
            return localCache.computeIfAbsent(sessionId, ConversationContext::new);
        }
    }
    
    /**
     * 保存对话上下文
     */
    public void saveContext(ConversationContext context) {
        try {
            String key = CONTEXT_KEY + context.getSessionId();
            redisTemplate.opsForValue().set(key, context, TTL);
        } catch (Exception e) {
            log.warn("[Conversation] 保存失败，使用本地缓存: {}", e.getMessage());
            localCache.put(context.getSessionId(), context);
        }
    }
    
    /**
     * 记录已展示的建议（避免重复）
     */
    public void recordShownSuggestions(String sessionId, List<String> suggestions) {
        ConversationContext context = getOrCreateContext(sessionId);
        context.addShownSuggestions(suggestions);
        saveContext(context);
    }
    
    /**
     * 过滤已展示过的建议
     */
    public List<String> filterShownSuggestions(String sessionId, List<String> suggestions) {
        ConversationContext context = getOrCreateContext(sessionId);
        return suggestions.stream()
                .filter(s -> !context.hasShown(s))
                .toList();
    }
    
    /**
     * 添加用户消息到历史
     */
    public void addUserMessage(String sessionId, String message) {
        ConversationContext context = getOrCreateContext(sessionId);
        context.addMessage("user", message);
        saveContext(context);
    }
    
    /**
     * 添加系统回复到历史
     */
    public void addSystemMessage(String sessionId, String message) {
        ConversationContext context = getOrCreateContext(sessionId);
        context.addMessage("system", message);
        saveContext(context);
    }
    
    /**
     * 获取最近 N 条对话历史
     */
    public List<Map<String, String>> getRecentHistory(String sessionId, int limit) {
        ConversationContext context = getOrCreateContext(sessionId);
        List<Map<String, String>> history = context.getHistory();
        
        int start = Math.max(0, history.size() - limit);
        return history.subList(start, history.size());
    }
    
    /**
     * 从会话历史中提取最近的地点信息
     * @deprecated Consumer 不再做地点提取,Router Service 会处理
     * @return null (始终返回 null)
     */
    @Deprecated
    public String extractLastLocation(String sessionId) {
        log.debug("[Conversation] extractLastLocation 已废弃,Consumer 不再提取地点");
        return null;
    }
    
    /**
     * 生成会话 ID
     */
    private String generateSessionId() {
        return "session_" + System.currentTimeMillis() + "_" + 
               UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * 对话上下文数据模型
     */
    public static class ConversationContext {
        private String sessionId;
        private Set<String> shownSuggestions = new HashSet<>(); // 已展示的建议
        private List<Map<String, String>> history = new ArrayList<>(); // 对话历史
        private long createdAt = System.currentTimeMillis();
        private long lastActiveAt = System.currentTimeMillis();
        
        public ConversationContext() {}
        
        public ConversationContext(String sessionId) {
            this.sessionId = sessionId;
        }
        
        /**
         * 添加已展示的建议
         */
        public void addShownSuggestions(List<String> suggestions) {
            shownSuggestions.addAll(suggestions);
            lastActiveAt = System.currentTimeMillis();
        }
        
        /**
         * 检查是否已展示过
         */
        public boolean hasShown(String suggestion) {
            return shownSuggestions.contains(suggestion);
        }
        
        /**
         * 添加消息到历史
         */
        public void addMessage(String role, String content) {
            Map<String, String> message = new HashMap<>();
            message.put("role", role);
            message.put("content", content);
            message.put("timestamp", String.valueOf(System.currentTimeMillis()));
            
            history.add(message);
            
            // 保留最近 20 条
            if (history.size() > 20) {
                history = history.subList(history.size() - 20, history.size());
            }
            
            lastActiveAt = System.currentTimeMillis();
        }
        
        // Getters and Setters
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public Set<String> getShownSuggestions() { return shownSuggestions; }
        public void setShownSuggestions(Set<String> suggestions) { this.shownSuggestions = suggestions; }
        public List<Map<String, String>> getHistory() { return history; }
        public void setHistory(List<Map<String, String>> history) { this.history = history; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public long getLastActiveAt() { return lastActiveAt; }
        public void setLastActiveAt(long lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    }
}
