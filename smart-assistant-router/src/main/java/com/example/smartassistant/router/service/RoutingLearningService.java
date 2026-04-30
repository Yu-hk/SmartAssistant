package com.example.smartassistant.router.service;

import com.example.smartassistant.router.mapper.RoutingHistoryMapper;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.RoutingHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由学习服务 - 基于历史反馈动态调整 Agent 优先级
 */
@Service
public class RoutingLearningService {
    
    private static final Logger log = LoggerFactory.getLogger(RoutingLearningService.class);
    
    private final RoutingHistoryMapper routingHistoryMapper;
    
    public RoutingLearningService(RoutingHistoryMapper routingHistoryMapper) {
        this.routingHistoryMapper = routingHistoryMapper;
    }
    
    /**
     * 记录路由决策
     */
    @Transactional
    public Long recordRouting(String question, String agentName, String routingMethod, Double confidence) {
        RoutingHistory history = new RoutingHistory(question, agentName, routingMethod, confidence);
        routingHistoryMapper.insert(history);
        log.debug("[RoutingLearning] 记录路由: agent={}, method={}", agentName, routingMethod);
        return history.getId();
    }
    
    /**
     * 更新路由结果（成功/失败）
     */
    @Transactional
    public void updateResult(Long historyId, boolean success, long responseTimeMs) {
        int updated = routingHistoryMapper.updateResult(historyId, success, responseTimeMs);
        if (updated > 0) {
            log.debug("[RoutingLearning] 更新结果: id={}, success={}, time={}ms", 
                    historyId, success, responseTimeMs);
        }
    }
    
    /**
     * 记录用户反馈
     */
    @Transactional
    public void recordFeedback(Long historyId, String feedback) {
        int updated = routingHistoryMapper.updateFeedback(historyId, feedback);
        if (updated > 0) {
            log.info("[RoutingLearning] 记录用户反馈: id={}, feedback={}", historyId, feedback);
        }
    }
    
    /**
     * 获取 Agent 的成功率（最近7天）
     */
    public double getSuccessRate(String agentName) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        Double rate = routingHistoryMapper.getSuccessRate(agentName, since);
        return rate != null ? rate : 50.0; // 默认 50%
    }
    
    /**
     * 获取 Agent 的平均响应时间（最近7天）
     */
    public double getAvgResponseTime(String agentName) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        Double avgTime = routingHistoryMapper.getAvgResponseTime(agentName, since);
        return avgTime != null ? avgTime : 1000.0; // 默认 1000ms
    }
    
    /**
     * 根据历史表现调整 Agent 优先级
     */
    public Map<String, Double> calculateAdjustedPriorities(List<DiscoveredAgent> agents) {
        Map<String, Double> adjustedPriorities = new HashMap<>();
        
        for (DiscoveredAgent agent : agents) {
            String agentName = agent.getServiceName();
            
            // 基础优先级（来自元数据）
            double basePriority = agent.getMetadata().getPriority() != null ? 
                    agent.getMetadata().getPriority() : 5.0;
            
            // 成功率加成（0-5分）
            double successRate = getSuccessRate(agentName);
            double successBonus = (successRate / 100.0) * 5.0;
            
            // 响应时间惩罚（越快越好，0-2分）
            double avgResponseTime = getAvgResponseTime(agentName);
            double speedBonus = Math.max(0, 2.0 - (avgResponseTime / 1000.0));
            
            // 调整后优先级 = 基础 + 成功率加成 + 速度加成
            double adjustedPriority = basePriority + successBonus + speedBonus;
            
            adjustedPriorities.put(agentName, adjustedPriority);
            
            log.debug("[RoutingLearning] Agent {} 优先级调整: 基础={}, 成功率={}%, 响应={}ms, 调整后={}",
                    agentName, basePriority, successRate, avgResponseTime, adjustedPriority);
        }
        
        return adjustedPriorities;
    }
    
    /**
     * 获取路由统计信息
     */
    public Map<String, Object> getRoutingStats() {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        
        Map<String, Object> stats = new HashMap<>();
        
        // 总路由次数
        long totalCount = routingHistoryMapper.totalCount();
        stats.put("totalRoutes", totalCount);
        
        // 各 Agent 路由次数
        List<Map<String, Object>> agentCounts = routingHistoryMapper.countByAgent(since);
        Map<String, Long> routesByAgent = new HashMap<>();
        for (Map<String, Object> row : agentCounts) {
            routesByAgent.put((String) row.get("routed_agent"), ((Number) row.get("count")).longValue());
        }
        stats.put("routesByAgent", routesByAgent);
        
        // 整体成功率
        List<RoutingHistory> recentHistories = routingHistoryMapper.findRecent(10);
        long successCount = recentHistories.stream()
                .filter(h -> Boolean.TRUE.equals(h.getIsSuccess()))
                .count();
        double overallSuccessRate = recentHistories.isEmpty() ? 0 : 
                (successCount * 100.0 / recentHistories.size());
        stats.put("overallSuccessRate", overallSuccessRate);
        
        return stats;
    }
}
