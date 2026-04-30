package com.example.smartassistant.router.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 路由请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteRequest {
    
    /**
     * 用户 ID
     */
    private Long userId;
    
    /**
     * 用户问题（已包含用户画像的完整 Prompt）
     */
    private String question;
    
    /**
     * 会话 ID（可选）
     */
    private String sessionId;
    
    /**
     * 是否启用 RAG 增强
     */
    @Builder.Default
    private Boolean enableRag = false;
    
    /**
     * ⭐ 请求 ID（用于 Redis 存储）
     * 可选，如果不提供会自动生成
     */
    private String requestId;
}
