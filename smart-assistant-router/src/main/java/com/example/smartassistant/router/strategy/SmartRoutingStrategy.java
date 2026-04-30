package com.example.smartassistant.router.strategy;

import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.RouteDecision;
import com.example.smartassistant.router.service.AgentDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 智能评分路由策略
 * 基于多维度评分的确定性路由算法
 */
@Component
public class SmartRoutingStrategy implements RoutingStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(SmartRoutingStrategy.class);
    
    private final AgentDiscoveryService agentDiscoveryService;
    
    @Value("${router.smart-routing.min-score:30}")
    private double minScore;
    
    public SmartRoutingStrategy(AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }
    
    @Override
    public RouteDecision route(String userInput, Map<String, Object> context) {
        log.debug("[SmartRouting] 执行智能评分路由");
        
        try {
            // 从 Nacos 动态获取所有可用的 Agent
            List<DiscoveredAgent> availableAgents = agentDiscoveryService.discoverAllAgents();
            
            if (availableAgents.isEmpty()) {
                log.warn("[SmartRouting] 未找到可用的 Agent");
                return null;
            }
            
            // 计算每个 Agent 的得分
            Map<String, Double> scores = new HashMap<>();
            
            for (DiscoveredAgent agent : availableAgents) {
                String agentName = agent.getAgentName();
                double score = calculateScore(userInput, agent, context);
                scores.put(agentName, score);
                
                log.debug("[SmartRouting] Agent {} 得分: {}", agentName, score);
            }
            
            // 选择得分最高的 Agent
            Optional<Map.Entry<String, Double>> bestEntry = scores.entrySet().stream()
                    .max(Map.Entry.comparingByValue());
            
            if (bestEntry.isEmpty()) {
                log.warn("[SmartRouting] 无法计算得分");
                return null;
            }
            
            String bestAgent = bestEntry.get().getKey();
            double bestScore = bestEntry.get().getValue();
            
            log.info("[SmartRouting] 路由决策完成: bestAgent={}, score={}", bestAgent, bestScore);
            
            // 如果最高分低于阈值，返回 null（让其他策略处理）
            if (bestScore < minScore) {
                log.warn("[SmartRouting] 最高分 {} 低于阈值 {}，放弃路由", bestScore, minScore);
                return null;
            }
            
            // 构建路由决策
            RouteDecision decision = new RouteDecision();
            decision.setAgentName(bestAgent);
            decision.setConfidence(bestScore / 100.0); // 归一化到 0-1
            decision.setRoutingMethod("SMART_ROUTING");
            
            return decision;
            
        } catch (Exception e) {
            log.error("[SmartRouting] 智能路由失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public String getStrategyName() {
        return "SMART_ROUTING";
    }
    
    @Override
    public int getPriority() {
        return 3; // 较低优先级，作为降级方案
    }
    
    /**
     * 计算 Agent 得分
     */
    private double calculateScore(String userInput, DiscoveredAgent agent, Map<String, Object> context) {
        double score = 0.0;
        
        // 1. 关键词匹配（40%）
        double keywordScore = calculateKeywordScore(userInput, agent);
        score += keywordScore * 0.4;
        
        // 2. 意图匹配（30%）
        double intentScore = calculateIntentScore(context, agent);
        score += intentScore * 0.3;
        
        // 3. 上下文继承（20%）
        double contextScore = calculateContextScore(context, agent);
        score += contextScore * 0.2;
        
        // 4. Agent 优先级（10%）
        int priority = extractPriority(agent);
        score += priority * 0.5;
        
        return score;
    }
    
    /**
     * 关键词匹配得分
     */
    private double calculateKeywordScore(String userInput, DiscoveredAgent agent) {
        List<String> keywords = extractKeywords(agent);
        
        if (keywords.isEmpty()) {
            return 0;
        }
        
        int matchCount = 0;
        for (String keyword : keywords) {
            if (userInput.contains(keyword)) {
                matchCount++;
            }
        }
        
        double maxPossible = keywords.size();
        return maxPossible > 0 ? (matchCount / maxPossible) * 100 : 0;
    }
    
    /**
     * 意图匹配得分
     */
    private double calculateIntentScore(Map<String, Object> context, DiscoveredAgent agent) {
        if (context == null || !context.containsKey("currentIntent")) {
            return 0;
        }
        
        String currentIntent = (String) context.get("currentIntent");
        List<String> intents = extractIntents(agent);
        
        if (intents.contains(currentIntent)) {
            return 100.0;
        }
        
        return 0;
    }
    
    /**
     * 上下文继承得分
     */
    private double calculateContextScore(Map<String, Object> context, DiscoveredAgent agent) {
        if (context == null) {
            return 0;
        }
        
        double score = 0;
        String agentName = agent.getAgentName();
        
        // 如果上下文中有城市信息
        if (context.containsKey("currentCity") && context.get("currentCity") != null) {
            if (agentName.contains("location") || agentName.contains("weather") ||
                agentName.contains("food") || agentName.contains("travel")) {
                score += 50;
            }
        }
        
        // 如果上下文中记录了上次使用的 Agent
        if (context.containsKey("currentAgent")) {
            String lastAgent = (String) context.get("currentAgent");
            if (agentName.equals(lastAgent)) {
                score += 30; // 连续性奖励
            }
        }
        
        return Math.min(score, 100);
    }
    
    /**
     * 从 Agent 元数据中提取关键词
     */
    private List<String> extractKeywords(DiscoveredAgent agent) {
        if (agent.getMetadata() == null || agent.getMetadata().getKeywords() == null) {
            return Collections.emptyList();
        }
        
        String keywordsStr = agent.getMetadata().getKeywords();
        if (keywordsStr.isEmpty()) {
            return Collections.emptyList();
        }
        
        return Arrays.stream(keywordsStr.split(","))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .toList();
    }
    
    /**
     * 从 Agent 元数据中提取意图
     */
    private List<String> extractIntents(DiscoveredAgent agent) {
        if (agent.getMetadata() == null || agent.getMetadata().getCapabilities() == null) {
            return Collections.emptyList();
        }
        
        String intentsStr = agent.getMetadata().getCapabilities();
        if (intentsStr.isEmpty()) {
            return Collections.emptyList();
        }
        
        return Arrays.stream(intentsStr.split(","))
                .map(String::trim)
                .filter(i -> !i.isEmpty())
                .toList();
    }
    
    /**
     * 从 Agent 元数据中提取优先级
     */
    private int extractPriority(DiscoveredAgent agent) {
        if (agent.getMetadata() == null || agent.getMetadata().getPriority() == null) {
            return 5; // 默认优先级
        }
        
        return agent.getMetadata().getPriority();
    }
}
