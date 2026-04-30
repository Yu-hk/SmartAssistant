package com.example.smartassistant.router.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 路由执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingResult {

    /**
     * Agent 处理结果文本
     */
    private String result;

    /**
     * 目标 Agent 名称
     */
    private String agentName;

    /**
     * 置信度 (0.0 - 1.0)
     */
    private Double confidence;

    /**
     * 意图标签（用于用户画像意图分布统计）
     */
    private String intentTag;


}
