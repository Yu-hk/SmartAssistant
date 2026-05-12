/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 智能路由服务（基于多维度评分）
 * 作为 LLM 路由的备选方案，提供确定性的路由决策
 */
@Service
public class SmartRoutingService {

    private static final Logger log = LoggerFactory.getLogger(SmartRoutingService.class);

    private final AgentDiscoveryService agentDiscoveryService;

    public SmartRoutingService(AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }

    /**
     * 智能路由决策
     * 
     * @param userInput 用户输入
     * @param context 对话上下文
     * @return 目标 Agent 名称
     */
    public String route(String userInput, Map<String, Object> context) {
        log.info("[SmartRouting] 开始路由决策: input={}, context={}", userInput, context);

        // 从 Nacos 动态获取所有可用的 Agent
        List<DiscoveredAgent> availableAgents = agentDiscoveryService.discoverAllAgents();
        
        if (availableAgents.isEmpty()) {
            log.warn("[SmartRouting] 未找到可用的 Agent，使用默认路由");
            return "location-weather-agent";
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
        String bestAgent = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown_agent");

        double bestScore = scores.getOrDefault(bestAgent, 0.0);
        
        log.info("[SmartRouting] 路由决策完成: bestAgent={}, score={}", bestAgent, bestScore);

        // 如果最高分低于阈值，返回默认 Agent
        if (bestScore < 30) {
            log.warn("[SmartRouting] 所有 Agent 得分均较低，使用默认 Agent");
            return "location-weather-agent";
        }

        return bestAgent;
    }

    /**
     * 计算 Agent 得分
     */
    private double calculateScore(String userInput, DiscoveredAgent agent, Map<String, Object> context) {
        double score = 0.0;

        // 1. 关键词匹配（基础分）
        double keywordScore = calculateKeywordScore(userInput, agent);
        score += keywordScore * 0.4; // 权重 40%

        // 2. 意图匹配
        double intentScore = calculateIntentScore(context, agent);
        score += intentScore * 0.3; // 权重 30%

        // 3. 上下文继承
        double contextScore = calculateContextScore(context, agent);
        score += contextScore * 0.2; // 权重 20%

        // 4. Agent 优先级（从元数据获取）
        int priority = extractPriority(agent);
        score += priority * 0.5; // 权重调整

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

        // 归一化到 0-100
        double maxPossible = keywords.size();
        return matchCount / maxPossible * 100;
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

        // 如果上下文中有城市信息，且该 Agent 支持位置相关功能
        if (context.containsKey("currentCity") && context.get("currentCity") != null) {
            if (agentName.contains("location") || agentName.contains("weather") ||
                agentName.contains("food") || agentName.contains("travel")) {
                score += 50;
            }
        }

        // 如果上下文中记录了上次使用的 Agent，给予一定权重
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
     * 从 Agent 元数据中提取意图（使用 capabilities）
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
