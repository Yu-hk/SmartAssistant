package com.example.smartassistant.consumer.service.recommendation.strategy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 交通相关建议策略
 * 场景: 用户查询交通/出行方式 → 建议天气、美食、旅游
 */
@Component
public class TransportRelatedStrategy extends BaseSuggestionStrategy {
    
    private static final Set<String> TRANSPORT_KEYWORDS = Set.of(
            "怎么去", "如何到达", "交通", "出行方式", "地铁", "公交",
            "打车", "骑车", "走路", "路线", "导航", "通勤"
    );
    
    public TransportRelatedStrategy() {
        super(TRANSPORT_KEYWORDS);
    }
    
    @Override
    public boolean isApplicable(String question, String location) {
        return containsKeyword(question);
    }
    
    @Override
    public List<String> generateSuggestions(String question, String location) {
        List<String> suggestions = new ArrayList<>();
        
        suggestions.add("帮我推荐最佳的出行方式");
        
        if (location != null && !location.isEmpty()) {
            suggestions.add("查询 " + location + " 的天气情况");
            suggestions.add("推荐 " + location + " 附近的美食");
            suggestions.add("为我制定 " + location + " 的旅游攻略");
        }
        
        return suggestions;
    }
    
    @Override
    public int getPriority() {
        return 20; // 中等优先级
    }
    
    @Override
    public String getStrategyName() {
        return "TransportRelatedStrategy";
    }
}
