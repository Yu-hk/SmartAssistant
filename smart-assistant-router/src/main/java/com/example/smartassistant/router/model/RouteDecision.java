/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 路由决策结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteDecision {
    
    /**
     * 目标 Agent 名称
     */
    private String agentName;
    
    /**
     * 置信度 (0.0 - 1.0)
     */
    private Double confidence;
    
    /**
     * 路由理由
     */
    private String reason;
    
    /**
     * 路由方式（LLM_ROUTING / KEYWORD_ROUTING / DISCOVERY_ROUTING）
     */
    private String routingMethod;
    
    /**
     * 提取的上下文信息（地点、意图等）
     */
    private ExtractedContext extractedContext;
    
    /**
     * 提取的上下文信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractedContext {
        /**
         * 地点信息（如：河北、北京）
         */
        private String location;
        
        /**
         * 用户意图（如：美食推荐、天气查询）
         */
        private String intent;
        
        /**
         * 时间范围（如：明天、下周）
         */
        private String timeRange;
        
        /**
         * 其他额外参数
         */
        private Map<String, Object> additionalParams;
    }
    
}
