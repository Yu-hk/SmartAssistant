package com.example.smartassistant.consumer.service.recommendation.strategy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 美食相关建议策略
 * 场景: 用户查询美食 → 建议旅游规划、天气查询
 */
@Component
public class FoodRelatedStrategy extends BaseSuggestionStrategy {
    
    private static final Set<String> FOOD_KEYWORDS = Set.of(
            "美食", "餐厅", "吃什么", "特色菜", "小吃", "火锅", "烧烤",
            "推荐餐厅", "附近美食", "菜系", "好吃"
    );
    
    public FoodRelatedStrategy() {
        super(FOOD_KEYWORDS);
    }
    
    @Override
    public boolean isApplicable(String question, String location) {
        return containsKeyword(question);
    }
    
    @Override
    public List<String> generateSuggestions(String question, String location) {
        List<String> suggestions = new ArrayList<>();
        
        if (location != null && !location.isEmpty()) {
            suggestions.add("帮我查询 " + location + " 的天气情况");
            suggestions.add("为我制定一份 " + location + " 的出行攻略");
        } else {
            suggestions.add("帮我推荐附近的餐厅");
        }
        
        return suggestions;
    }
    
    @Override
    public int getPriority() {
        return 10; // 高优先级
    }
    
    @Override
    public String getStrategyName() {
        return "FoodRelatedStrategy";
    }
}
