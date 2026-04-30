package com.example.smartassistant.consumer.service.session;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.smartassistant.consumer.entity.ChatMessage;
import com.example.smartassistant.consumer.mapper.ChatMessageMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 聊天消息持久化服务 (MyBatis Plus)
 */
@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    private final ChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public ChatMessageService(ChatMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 保存用户消息
     */
    @Transactional
    public void saveUserMessage(String sessionId, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setIsUser(true);
        message.setContent(content);
        // ⭐ 兼容旧表结构: thread_id 不能为 null
        try {
            messageMapper.insert(message);
            log.debug("[ChatMessage] 保存用户消息: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("[ChatMessage] 保存用户消息失败: {}", e.getMessage());
        }
    }

    /**
     * 保存 AI 回复
     */
    @Transactional
    public void saveAiMessage(String sessionId, String content, String targetAgent, Integer turnCount) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setIsUser(false);
        message.setContent(content);
        message.setTargetAgent(targetAgent);
        message.setTurnCount(turnCount);
        // ⭐ 兼容旧表结构: thread_id 不能为 null
        try {
            messageMapper.insert(message);
            log.debug("[ChatMessage] 保存AI回复: sessionId={}, agent={}", sessionId, targetAgent);
        } catch (Exception e) {
            log.warn("[ChatMessage] 保存AI回复失败: {}", e.getMessage());
        }
    }

    /**
     * 获取会话的完整历史（按时间升序）
     */
    public List<Map<String, Object>> getMessageHistory(String sessionId) {
        List<ChatMessage> messages = messageMapper.findBySessionIdOrderByCreatedAtAsc(sessionId);
        
        return messages.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * 获取最近N条消息
     */
    public List<Map<String, Object>> getRecentMessages(String sessionId, int limit) {
        List<ChatMessage> messages = messageMapper.findTop50BySessionIdOrderByCreatedAtDesc(sessionId);
        
        // 反转列表，使其按时间升序
        Collections.reverse(messages);
        
        // 限制数量
        List<ChatMessage> limited = messages.size() > limit 
                ? messages.subList(messages.size() - limit, messages.size())
                : messages;
        
        return limited.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * 删除会话的所有消息
     */
    @Transactional
    public void deleteSessionMessages(String sessionId) {
        messageMapper.deleteBySessionId(sessionId);
        log.info("[ChatMessage] 删除会话消息: sessionId={}", sessionId);
    }

    /**
     * 统计会话消息数
     */
    public long getMessageCount(String sessionId) {
        return messageMapper.countBySessionId(sessionId);
    }

    /**
     * 转换为 Map
     */
    private Map<String, Object> convertToMap(ChatMessage message) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", message.getId());
        map.put("sessionId", message.getSessionId());
        map.put("isUser", message.getIsUser());
        map.put("content", message.getContent());
        map.put("targetAgent", message.getTargetAgent());
        map.put("turnCount", message.getTurnCount());
        map.put("createdAt", message.getCreatedAt());
        
        if (message.getMetadata() != null) {
            try {
                map.put("metadata", objectMapper.readValue(message.getMetadata(), Map.class));
            } catch (JsonProcessingException e) {
                log.warn("[ChatMessage] 解析元数据失败", e);
            }
        }
        
        return map;
    }

    /**
     * 搜索消息（关键词）
     */
    public List<Map<String, Object>> searchMessages(String sessionId, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return getMessageHistory(sessionId);
        }
        
        List<ChatMessage> messages = messageMapper.searchByKeyword(sessionId, keyword.trim());
        Collections.reverse(messages); // 反转为升序
        
        return messages.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * 按 Agent 过滤消息
     */
    public List<Map<String, Object>> filterByAgent(String sessionId, String targetAgent) {
        List<ChatMessage> messages = messageMapper.findBySessionIdAndTargetAgentOrderByCreatedAtDesc(sessionId, targetAgent);
        Collections.reverse(messages);
        
        return messages.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * 只获取用户消息
     */
    public List<Map<String, Object>> getUserMessages(String sessionId) {
        List<ChatMessage> messages = messageMapper.findBySessionIdAndIsUserTrueOrderByCreatedAtDesc(sessionId);
        Collections.reverse(messages);
        
        return messages.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * 只获取 AI 回复
     */
    public List<Map<String, Object>> getAiMessages(String sessionId) {
        List<ChatMessage> messages = messageMapper.findBySessionIdAndIsUserFalseOrderByCreatedAtDesc(sessionId);
        Collections.reverse(messages);
        
        return messages.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * 按时间范围查询
     */
    public List<Map<String, Object>> getMessagesByTimeRange(
            String sessionId, LocalDateTime startTime, LocalDateTime endTime) {
        List<ChatMessage> messages = messageMapper.findBySessionIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                sessionId, startTime, endTime);
        
        return messages.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * 分页查询消息历史
     */
    public Map<String, Object> getMessagesPaged(String sessionId, int page, int size) {
        Page<ChatMessage> mybatisPage = new Page<>(page + 1, size); // MyBatis Plus 页码从 1 开始
        com.baomidou.mybatisplus.core.metadata.IPage<ChatMessage> messagePage = 
            messageMapper.findBySessionIdOrderByCreatedAtDesc(mybatisPage, sessionId);
        
        List<Map<String, Object>> messages = messagePage.getRecords().stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
        
        // 反转为升序
        Collections.reverse(messages);
        
        Map<String, Object> result = new HashMap<>();
        result.put("messages", messages);
        result.put("totalElements", messagePage.getTotal());
        result.put("totalPages", messagePage.getPages());
        result.put("currentPage", page);
        result.put("pageSize", size);
        result.put("hasNext", messagePage.getCurrent() < messagePage.getPages());
        result.put("hasPrevious", messagePage.getCurrent() > 1);
        
        return result;
    }
}
