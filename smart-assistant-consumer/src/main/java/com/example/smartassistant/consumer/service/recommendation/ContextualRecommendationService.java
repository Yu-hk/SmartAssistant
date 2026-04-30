package com.example.smartassistant.consumer.service.recommendation;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 上下文感知推荐服务
 * 根据对话上下文、时间、地点等因素生成个性化建议
 */
@Service
public class ContextualRecommendationService {

    // 地点提取正则
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(广州|深圳|成都|杭州|南京|武汉|西安|长沙|青岛|厦门|三亚|" +
                    "昆明|大理|丽江|桂林|苏州|无锡|宁波|哈尔滨|沈阳|大连|郑州|济南|" +
                    "合肥|福州|南昌|贵阳|南宁|海口|拉萨|乌鲁木齐|兰州|西宁|银川|呼和浩特|" +
                    "河北|河南|山东|山西|湖南|湖北|广东|广西|江苏|浙江|安徽|福建|江西|" +
                    "四川|贵州|云南|陕西|甘肃|青海|黑龙江|吉林|辽宁|海南|台湾|内蒙古|" +
                    "宁夏|新疆|西藏|北京|上海|天津|重庆)"
    );
    
    /**
     * 基于上下文生成推荐
     * 
     * @param currentQuestion 当前问题
     * @param conversationHistory 对话历史
     * @return 上下文相关的建议
     */
    public List<String> generateContextualSuggestions(String currentQuestion, 
                                                       List<Map<String, String>> conversationHistory) {
        List<String> suggestions = new ArrayList<>();
        
        // 1. 从历史中提取地点
        String location = extractLocationFromHistory(conversationHistory);
        
        // 2. 分析当前问题的意图
        String intent = detectIntent(currentQuestion);
        
        // 3. 基于意图和地点生成建议
        switch (intent) {
            case "FOOD" -> suggestions.addAll(generateFoodSuggestions(location));
            case "TRAVEL" -> suggestions.addAll(generateTravelSuggestions(location));
            case "WEATHER" -> suggestions.addAll(generateWeatherSuggestions(location));
            case "TRANSPORT" -> suggestions.addAll(generateTransportSuggestions(location));
        }
        
        // 4. 如果没有生成建议,使用默认建议
        if (suggestions.isEmpty() && location != null) {
            suggestions.add("查询 " + location + " 的美食推荐");
            suggestions.add("了解 " + location + " 的旅游景点");
        }
        
        return suggestions;
    }
    
    /**
     * 从对话历史中提取最近的地点
     */
    private String extractLocationFromHistory(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        
        // 从后往前查找
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> message = history.get(i);
            String content = message.get("content");
            
            if (content != null) {
                var matcher = LOCATION_PATTERN.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        
        return null;
    }
    
    /**
     * 检测问题意图
     */
    private String detectIntent(String question) {
        if (question == null) return "GENERAL";
        
        String lower = question.toLowerCase();
        
        if (lower.contains("美食") || lower.contains("餐厅") || lower.contains("吃")) {
            return "FOOD";
        } else if (lower.contains("旅游") || lower.contains("景点") || lower.contains("玩")) {
            return "TRAVEL";
        } else if (lower.contains("天气") || lower.contains("气温")) {
            return "WEATHER";
        } else if (lower.contains("交通") || lower.contains("怎么去") || lower.contains("地铁")) {
            return "TRANSPORT";
        }
        
        return "GENERAL";
    }
    
    /**
     * 生成美食相关建议
     */
    private List<String> generateFoodSuggestions(String location) {
        List<String> suggestions = new ArrayList<>();
        
        if (location != null) {
            suggestions.add("推荐 " + location + " 的特色小吃");
            suggestions.add("查询 " + location + " 的高评分餐厅");
            suggestions.add("了解 " + location + " 的饮食文化");
        }
        
        return suggestions;
    }
    
    /**
     * 生成旅游相关建议
     */
    private List<String> generateTravelSuggestions(String location) {
        List<String> suggestions = new ArrayList<>();
        
        if (location != null) {
            suggestions.add("规划 " + location + " 的一日游路线");
            suggestions.add("查询 " + location + " 的门票价格");
            suggestions.add("了解 " + location + " 的最佳游览时间");
        }
        
        return suggestions;
    }
    
    /**
     * 生成天气相关建议
     */
    private List<String> generateWeatherSuggestions(String location) {
        List<String> suggestions = new ArrayList<>();
        
        if (location != null) {
            suggestions.add("查询 " + location + " 未来一周的天气");
            suggestions.add("获取 " + location + " 的穿衣建议");
            suggestions.add("了解 " + location + " 的气候特点");
        }
        
        return suggestions;
    }
    
    /**
     * 生成交通相关建议
     */
    private List<String> generateTransportSuggestions(String location) {
        List<String> suggestions = new ArrayList<>();
        
        if (location != null) {
            suggestions.add("查询 " + location + " 的公共交通线路");
            suggestions.add("了解 " + location + " 的打车费用");
            suggestions.add("获取 " + location + " 的停车信息");
        }
        
        return suggestions;
    }
}
