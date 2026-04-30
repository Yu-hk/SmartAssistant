package com.example.smartassistant.router.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 路由响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteResponse {

    /**
     * 目标 Agent 名称
     */
    private String agentName;

    /**
     * 处理结果
     */
    private String result;

    /**
     * 置信度 (0.0 - 1.0)
     */
    private Double confidence;

    /**
     * 路由方式（LLM_ROUTING / KEYWORD_ROUTING）
     */
    private String routingMethod;

    /**
     * 意图标签（用于用户画像意图分布统计）
     */
    private String intentTag;

    /**
     * 错误信息（如果有）
     */
    private String error;
}
