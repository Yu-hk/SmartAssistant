package com.example.smartassistant.router.strategy;

import com.example.smartassistant.router.model.RouteDecision;

import java.util.Map;

/**
 * 路由策略接口
 * 所有路由策略必须实现此接口
 */
public interface RoutingStrategy {
    
    /**
     * 执行路由决策
     * 
     * @param userInput 用户输入
     * @param context 对话上下文
     * @return 路由决策结果，如果无法决策返回 null
     */
    RouteDecision route(String userInput, Map<String, Object> context);
    
    /**
     * 获取策略名称
     */
    String getStrategyName();
    
    /**
     * 获取策略优先级（数值越小优先级越高）
     */
    int getPriority();
    
    /**
     * 是否启用该策略
     */
    default boolean isEnabled() {
        return true;
    }
}
