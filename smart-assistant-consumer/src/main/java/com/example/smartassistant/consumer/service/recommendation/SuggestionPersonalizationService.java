package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 个性化建议服务
 * 基于用户行为和偏好对建议进行排序和优化
 * <p>
 * 使用中文分词器增强意图识别能力
 */
@Service
public class SuggestionPersonalizationService {
    
    private static final Logger log = LoggerFactory.getLogger(SuggestionPersonalizationService.class);
    
    /** ⭐ 中文分词器 */
    private final ChineseTokenizer tokenizer;
    
    // 用户行为记录: userId -> (behaviorType -> count)
    private final Map<String, Map<String, Integer>> userBehaviorHistory = new ConcurrentHashMap<>();
    
    // 用户偏好权重: userId -> (intent -> weight)
    private final Map<String, Map<String, Double>> userPreferences = new ConcurrentHashMap<>();
    
    public SuggestionPersonalizationService(ChineseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }
    
    /**
     * 个性化排序建议
     * 
     * @param userId 用户ID
     * @param suggestions 原始建议列表
     * @param suggestionIntents 建议与意图的映射
     * @return 个性化排序后的建议
     */
    public List<String> personalizeSuggestions(String userId, 
                                                List<String> suggestions,
                                                Map<String, String> suggestionIntents) {
        if (suggestions == null || suggestions.isEmpty()) {
            return suggestions;
        }
        
        // 如果没有用户偏好,返回原始顺序
        if (!userPreferences.containsKey(userId)) {
            return suggestions;
        }
        
        Map<String, Double> preferences = userPreferences.get(userId);
        
        // 计算每个建议的得分
        Map<String, Double> scoredSuggestions = new LinkedHashMap<>();
        
        for (String suggestion : suggestions) {
            String intent = suggestionIntents.getOrDefault(suggestion, "GENERAL");
            double weight = preferences.getOrDefault(intent, 1.0);
            scoredSuggestions.put(suggestion, weight);
        }
        
        // 按得分降序排序
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(scoredSuggestions.entrySet());
        sortedEntries.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));
        
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sortedEntries) {
            result.add(entry.getKey());
        }
        
        log.debug("[Personalization] userId={}, 原始{}条, 个性化后{}条", 
                userId, suggestions.size(), result.size());
        
        return result;
    }
    
    /**
     * 记录用户行为
     * 
     * @param userId 用户ID
     * @param behaviorType 行为类型(CLICKED, IGNORED, EXECUTED)
     * @param item 行为对象(建议内容或ID)
     */
    public void recordBehavior(String userId, String behaviorType, String item) {
        if (userId == null || "anonymous".equals(userId)) {
            return;
        }
        
        // 记录行为次数
        userBehaviorHistory.computeIfAbsent(userId, k -> new HashMap<>())
                .merge(behaviorType, 1, Integer::sum);
        
        // 更新偏好权重
        updatePreferenceWeights(userId, behaviorType, item);
        
        log.debug("[Personalization] 记录行为: userId={}, type={}, item={}", 
                userId, behaviorType, item);
    }
    
    /**
     * 更新用户偏好权重
     */
    private void updatePreferenceWeights(String userId, String behaviorType, String item) {
        Map<String, Double> preferences = userPreferences.computeIfAbsent(userId, k -> new HashMap<>());
        
        // 根据行为类型调整权重
        double weightChange = 0.0;
        
        switch (behaviorType) {
            case "CLICKED":
                weightChange = 0.1; // 点击增加权重
                break;
            case "EXECUTED":
                weightChange = 0.2; // 执行大幅增加权重
                break;
            case "IGNORED":
                weightChange = -0.05; // 忽略降低权重
                break;
        }
        
        // 简单规则:根据建议内容推断意图
        String intent = inferIntentFromSuggestion(item);
        
        // 更新该意图的权重
        double currentWeight = preferences.getOrDefault(intent, 1.0);
        double newWeight = Math.max(0.1, Math.min(2.0, currentWeight + weightChange));
        preferences.put(intent, newWeight);
        
        log.debug("[Personalization] 更新权重: userId={}, intent={}, weight={} -> {}", 
                userId, intent, currentWeight, newWeight);
    }
    
    /**
     * 从建议内容推断意图
     * <p>
     * ⭐ 使用中文分词器增强识别能力，支持同义词和口语化表达
     */
    private String inferIntentFromSuggestion(String suggestion) {
        if (suggestion == null) return "GENERAL";
        
        // ⭐ 使用分词器进行关键词匹配
        // 天气意图
        if (tokenizer.containsAnyKeyword(suggestion, Set.of("天气", "气温", "温度", "下雨", "晴天"))) {
            return "WEATHER";
        }
        
        // 美食意图（增强：包含觅食、馆子、口语化表达）
        if (tokenizer.containsAnyKeyword(suggestion, Set.of("美食", "餐厅", "吃", "觅食", "馆子", "好吃的"))) {
            return "FOOD";
        }
        
        // 旅行意图（增强：包含打卡、溜娃等）
        if (tokenizer.containsAnyKeyword(suggestion, Set.of("旅游", "景点", "行程", "打卡", "溜娃", "游玩"))) {
            return "TRAVEL";
        }
        
        // 交通意图（增强：包含咋走、咋去等口语）
        if (tokenizer.containsAnyKeyword(suggestion, Set.of("交通", "地铁", "怎么去", "咋走", "咋去", "公交"))) {
            return "TRANSPORT";
        }
        
        return "GENERAL";
    }
    
    /**
     * 获取用户行为统计
     */
    public Map<String, Integer> getUserBehaviorStats(String userId) {
        return userBehaviorHistory.getOrDefault(userId, Collections.emptyMap());
    }
    
    /**
     * 获取用户偏好
     */
    public Map<String, Double> getUserPreferences(String userId) {
        return userPreferences.getOrDefault(userId, Collections.emptyMap());
    }
}
