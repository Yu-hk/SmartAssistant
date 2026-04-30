package com.example.smartassistant.consumer.service.recommendation.strategy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 旅游相关建议策略
 * 场景: 用户查询旅游/景点 → 建议美食推荐、天气查询
 */
@Component
public class TravelRelatedStrategy extends BaseSuggestionStrategy {
    
    private static final Set<String> TRAVEL_KEYWORDS = Set.of(
            "旅游", "旅行", "出去玩", "度假", "景点", "景区", "游玩", 
            "周末", "假期", "出行", "自驾游", "跟团"
    );
    
    public TravelRelatedStrategy() {
        super(TRAVEL_KEYWORDS);
    }
    
    @Override
    public boolean isApplicable(String question, String location) {
        return containsKeyword(question);
    }
    
    @Override
    public List<String> generateSuggestions(String question, String location) {
        List<String> suggestions = new ArrayList<>();
        
        if (location != null && !location.isEmpty()) {
            suggestions.add("推荐 " + location + " 的特色美食");
            suggestions.add("查询 " + location + " 近期的天气");
            suggestions.add("帮我规划详细的行程路线");
            suggestions.add("推荐去 " + location + " 的最佳交通方式");
        }
        
        return suggestions;
    }
    
    @Override
    public int getPriority() {
        return 10; // 高优先级
    }
    
    @Override
    public String getStrategyName() {
        return "TravelRelatedStrategy";
    }
}
