package com.example.smartassistant.consumer.service.recommendation.strategy;

import java.util.Set;

/**
 * 建议策略抽象基类
 * 提供关键词匹配的通用实现
 */
public abstract class BaseSuggestionStrategy implements SuggestionStrategy {
    
    protected final Set<String> keywords;
    
    public BaseSuggestionStrategy(Set<String> keywords) {
        this.keywords = keywords;
    }
    
    /**
     * 判断问题是否包含相关关键词
     */
    protected boolean containsKeyword(String question) {
        if (question == null || question.isEmpty()) {
            return false;
        }
        String lowerQuestion = question.toLowerCase();
        return keywords.stream().anyMatch(lowerQuestion::contains);
    }
}
