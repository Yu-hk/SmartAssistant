package com.example.smartassistant.consumer.service.recommendation.strategy;

import java.util.List;

/**
 * 智能建议策略接口
 * 每个策略负责识别特定场景并生成对应的建议
 */
public interface SuggestionStrategy {
    
    /**
     * 判断当前策略是否适用
     * 
     * @param question 用户问题
     * @param location 提取的地点(可为null)
     * @return 是否适用
     */
    boolean isApplicable(String question, String location);
    
    /**
     * 生成建议列表
     * 
     * @param question 用户问题
     * @param location 提取的地点(可为null)
     * @return 建议列表
     */
    List<String> generateSuggestions(String question, String location);
    
    /**
     * 策略优先级(数字越小优先级越高)
     * 
     * @return 优先级
     */
    default int getPriority() {
        return 100; // 默认优先级
    }
    
    /**
     * 策略名称(用于日志和调试)
     * 
     * @return 策略名称
     */
    String getStrategyName();
}
