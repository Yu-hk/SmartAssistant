package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.consumer.service.recommendation.strategy.SuggestionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 智能建议引擎 - 基于策略模式
 * 自动发现并执行所有适用的建议策略
 */
@Service
public class SuggestionEngine {
    
    private static final Logger log = LoggerFactory.getLogger(SuggestionEngine.class);
    
    private final List<SuggestionStrategy> strategies;
    
    // 地点提取正则
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(广州|深圳|成都|杭州|南京|武汉|西安|长沙|青岛|厦门|三亚|" +
                    "昆明|大理|丽江|桂林|苏州|无锡|宁波|哈尔滨|沈阳|大连|郑州|济南|" +
                    "合肥|福州|南昌|贵阳|南宁|海口|拉萨|乌鲁木齐|兰州|西宁|银川|呼和浩特|" +
                    "河北|河南|山东|山西|湖南|湖北|广东|广西|江苏|浙江|安徽|福建|江西|" +
                    "四川|贵州|云南|陕西|甘肃|青海|黑龙江|吉林|辽宁|海南|台湾|内蒙古|" +
                    "宁夏|新疆|西藏|北京|上海|天津|重庆)"
    );
    
    public SuggestionEngine(List<SuggestionStrategy> strategies) {
        this.strategies = strategies;
        
        // 按优先级排序
        this.strategies.sort(Comparator.comparingInt(SuggestionStrategy::getPriority));
        
        log.info("[SuggestionEngine] 初始化完成,加载 {} 个策略", strategies.size());
        strategies.forEach(s -> 
            log.debug("  - {} (priority={})", s.getStrategyName(), s.getPriority())
        );
    }
    
    /**
     * 生成智能建议
     * 
     * @param question 用户问题
     * @param currentAgent 当前路由到的 Agent (保留参数用于兼容性)
     * @return 建议列表
     */
    public List<String> generateSuggestions(String question, String currentAgent) {
        if (question == null || question.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 1. 提取地点
        String location = extractLocation(question);
        
        // 2. 执行所有适用的策略
        Set<String> allSuggestions = new LinkedHashSet<>(); // 使用 Set 去重,保持顺序
        
        for (SuggestionStrategy strategy : strategies) {
            if (strategy.isApplicable(question, location)) {
                List<String> suggestions = strategy.generateSuggestions(question, location);
                allSuggestions.addAll(suggestions);
                
                log.debug("[SuggestionEngine] 策略 {} 生成 {} 条建议", 
                        strategy.getStrategyName(), suggestions.size());
            }
        }
        
        List<String> result = new ArrayList<>(allSuggestions);
        
        log.info("[SuggestionEngine] 问题='{}', 地点={}, 生成{}条建议", 
                question, location, result.size());
        
        return result;
    }
    
    /**
     * 从问题中提取地点
     */
    private String extractLocation(String question) {
        var matcher = LOCATION_PATTERN.matcher(question);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 格式化建议为友好的回复文本
     */
    public String formatSuggestions(List<String> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder("\n\n💡 **智能建议**:\n");
        for (int i = 0; i < suggestions.size(); i++) {
            sb.append((i + 1)).append(". ").append(suggestions.get(i)).append("\n");
        }
        sb.append("\n你可以直接点击或回复序号来执行~");
        
        return sb.toString();
    }
}
