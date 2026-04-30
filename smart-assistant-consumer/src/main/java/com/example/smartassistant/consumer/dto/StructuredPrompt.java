package com.example.smartassistant.consumer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 结构化 Prompt DTO
 * 用于 Consumer 和 Router 之间的标准化通信
 * 版本历史：
 * v1.0 - 初始版本，支持用户画像、历史对话、当前问题
 * v1.1 - 添加元数据和压缩支持
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StructuredPrompt {
    
    /**
     * 协议版本号（用于兼容性检查）
     */
    private String version = "1.1";
    
    /**
     * 请求元数据
     */
    private RequestMetadata metadata;
    
    /**
     * 用户画像信息
     */
    private UserProfile userProfile;
    
    /**
     * 历史对话列表
     */
    private List<ConversationMessage> conversationHistory;
    
    /**
     * 当前问题
     */
    private String currentQuestion;
    
    /**
     * 是否启用压缩（针对超长历史对话）
     */
    private Boolean compressed = false;
    
    /**
     * 请求元数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestMetadata {
        /**
         * 请求唯一 ID（用于追踪）
         */
        private String requestId;
        
        /**
         * 时间戳
         */
        private Long timestamp;
        
        /**
         * 客户端标识（web-app/mobile-app/api）
         */
        private String clientId;
        
        /**
         * 用户 ID
         */
        private Long userId;
        
        /**
         * 会话 ID
         */
        private String sessionId;
        
        /**
         * 额外扩展字段（JSON 字符串）
         */
        private String extra;
    }
    
    /**
     * 用户画像
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserProfile {
        /**
         * 用户偏好描述
         */
        private String preferences;
        
        /**
         * 历史行为记录
         */
        private String historyBehavior;
        
        /**
         * 其他画像信息（JSON 字符串，便于扩展）
         */
        private String additionalInfo;
    }
    
    /**
     * 对话消息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationMessage {
        /**
         * 角色：user 或 agent
         */
        private String role;
        
        /**
         * 消息内容
         */
        private String content;
        
        /**
         * 时间戳（可选）
         */
        private Long timestamp;
    }
    
    /**
     * 转换为 JSON 字符串
     */
    public String toJson() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert StructuredPrompt to JSON", e);
        }
    }
    
    /**
     * 从 JSON 字符串解析
     */
    public static StructuredPrompt fromJson(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, StructuredPrompt.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse StructuredPrompt from JSON", e);
        }
    }
}
