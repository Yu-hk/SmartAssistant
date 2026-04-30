package com.example.smartassistant.consumer.service.recommendation.strategy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 天气相关建议策略
 * 场景: 用户查询天气 → 建议穿衣指南、出行建议
 */
@Component
public class WeatherRelatedStrategy extends BaseSuggestionStrategy {
    
    private static final Set<String> WEATHER_KEYWORDS = Set.of(
            "天气", "气温", "下雨", "晴天", "阴天", "温度", "气候",
            "适合出门", "穿衣"
    );
    
    public WeatherRelatedStrategy() {
        super(WEATHER_KEYWORDS);
    }
    
    @Override
    public boolean isApplicable(String question, String location) {
        return containsKeyword(question);
    }
    
    @Override
    public List<String> generateSuggestions(String question, String location) {
        List<String> suggestions = new ArrayList<>();
        
        suggestions.add("给我提供穿衣建议");
        
        if (location != null && !location.isEmpty()) {
            suggestions.add("推荐 " + location + " 适合的户外活动");
            suggestions.add("查询 " + location + " 附近的美食餐厅");
        }
        
        return suggestions;
    }
    
    @Override
    public int getPriority() {
        return 20; // 中等优先级
    }
    
    @Override
    public String getStrategyName() {
        return "WeatherRelatedStrategy";
    }
}
