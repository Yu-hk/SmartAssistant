package com.example.smartassistant.router.strategy;

import com.example.smartassistant.router.model.RouteDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 路由策略管理器
 * 负责管理和执行所有路由策略
 */
@Component
public class RoutingStrategyManager {
    
    private static final Logger log = LoggerFactory.getLogger(RoutingStrategyManager.class);
    
    private final List<RoutingStrategy> strategies;
    
    public RoutingStrategyManager(List<RoutingStrategy> strategies) {
        this.strategies = strategies;
        log.info("[StrategyManager] 初始化路由策略管理器，共 {} 个策略", strategies.size());
        
        // 打印所有策略
        strategies.stream()
                .sorted(Comparator.comparingInt(RoutingStrategy::getPriority))
                .forEach(strategy -> 
                    log.info("[StrategyManager] 注册策略: {} (优先级: {})", 
                            strategy.getStrategyName(), 
                            strategy.getPriority())
                );
    }
    
    /**
     * 执行路由决策（按优先级尝试所有策略）
     * 
     * @param userInput 用户输入
     * @param context 对话上下文
     * @return 路由决策结果
     */
    public RouteDecision executeRouting(String userInput, Map<String, Object> context) {
        log.info("[StrategyManager] 开始执行路由决策: inputLength={}", userInput.length());
        
        // 按优先级排序策略
        List<RoutingStrategy> sortedStrategies = strategies.stream()
                .filter(RoutingStrategy::isEnabled)
                .sorted(Comparator.comparingInt(RoutingStrategy::getPriority))
                .toList();
        
        // 依次尝试每个策略
        for (RoutingStrategy strategy : sortedStrategies) {
            log.debug("[StrategyManager] 尝试策略: {}", strategy.getStrategyName());
            
            long startTime = System.currentTimeMillis();
            RouteDecision decision = strategy.route(userInput, context);
            long duration = System.currentTimeMillis() - startTime;
            
            if (decision != null) {
                log.info("[StrategyManager] 策略 {} 路由成功: agent={}, confidence={}, duration={}ms",
                        strategy.getStrategyName(),
                        decision.getAgentName(),
                        decision.getConfidence(),
                        duration);
                return decision;
            }
            
            log.debug("[StrategyManager] 策略 {} 路由失败，尝试下一个策略 (耗时: {}ms)",
                    strategy.getStrategyName(), duration);
        }
        
        log.warn("[StrategyManager] 所有路由策略均失败");
        return null;
    }
    
    /**
     * 获取所有已注册的策略
     */
    public List<RoutingStrategy> getAllStrategies() {
        return strategies;
    }
    
    /**
     * 获取策略统计信息
     */
    public Map<String, Object> getStrategyStats() {
        return Map.of(
                "totalStrategies", strategies.size(),
                "enabledStrategies", strategies.stream().filter(RoutingStrategy::isEnabled).count(),
                "strategies", strategies.stream()
                        .sorted(Comparator.comparingInt(RoutingStrategy::getPriority))
                        .map(s -> Map.of(
                                "name", s.getStrategyName(),
                                "priority", s.getPriority(),
                                "enabled", s.isEnabled()
                        ))
                        .collect(Collectors.toList())
        );
    }
}
