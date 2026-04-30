package com.example.smartassistant.router.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 路由历史记录 - 用于学习路由成功率
 */
@Data
@NoArgsConstructor
public class RoutingHistory {
    
    private Long id;
    
    /**
     * 用户问题
     */
    private String question;
    
    /**
     * 路由到的 Agent 名称
     */
    private String routedAgent;
    
    /**
     * 路由方法：LLM_ROUTING, DISCOVERY_ROUTING, KEYWORD_ROUTING
     */
    private String routingMethod;
    
    /**
     * 置信度分数
     */
    private Double confidenceScore;
    
    /**
     * 是否成功（由后续反馈决定）
     */
    private Boolean isSuccess;
    
    /**
     * 用户反馈（可选）
     */
    private String userFeedback;
    
    /**
     * 响应时间（毫秒）
     */
    private Long responseTimeMs;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 构造函数
     */
    public RoutingHistory(String question, String routedAgent, String routingMethod, 
                         Double confidenceScore) {
        this.question = question;
        this.routedAgent = routedAgent;
        this.routingMethod = routingMethod;
        this.confidenceScore = confidenceScore;
        this.isSuccess = null; // 初始为未知
    }
}
