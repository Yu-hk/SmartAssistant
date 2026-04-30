package com.example.smartassistant.router.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 多意图路由响应
 * 支持返回多个目标 Agent
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiAgentRouteResponse {
    
    /**
     * 是否为多意图请求
     */
    private boolean multiIntent;
    
    /**
     * 目标 Agent 列表（按执行顺序排序）
     */
    @Builder.Default
    private List<AgentTarget> targets = new ArrayList<>();
    
    /**
     * 提取的上下文信息（地点、时间等）
     */
    private ExtractedContext context;
    
    /**
     * 路由方式
     */
    private String routingMethod;
    
    /**
     * 聚合后的结果（多意图时包含所有 Agent 的整合回答）
     */
    private String aggregatedResult;
    
    /**
     * 总执行时间（毫秒）
     */
    private Long executionTimeMs;
    
    /**
     * 错误信息
     */
    private String error;
    
    /**
     * 单个 Agent 目标
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentTarget {
        /**
         * Agent 服务名称（Nacos 中的 serviceName，作为 Agent 的唯一标识）
         */
        private String serviceName;
        
        /**
         * 该 Agent 对应的问题（可能经过重写）
         */
        private String question;
        
        /**
         * 置信度 (0.0 - 1.0)
         */
        private Double confidence;
        
        /**
         * 执行顺序
         */
        private Integer order;
        
        /**
         * 是否可并行执行
         */
        @Builder.Default
        private boolean parallelizable = true;
    }
    
    /**
     * 提取的上下文
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractedContext {
        private String location;
        private String time;
        private String additionalInfo;
    }
    
    /**
     * 创建单 Agent 响应（兼容旧接口）
     */
    public static MultiAgentRouteResponse singleTarget(String serviceName, String question, 
                                                        Double confidence) {
        return MultiAgentRouteResponse.builder()
                .multiIntent(false)
                .targets(List.of(AgentTarget.builder()
                        .serviceName(serviceName)
                        .question(question)
                        .confidence(confidence)
                        .order(1)
                        .parallelizable(false)
                        .build()))
                .routingMethod("SINGLE_ROUTING")
                .build();
    }
}
