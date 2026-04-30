package com.example.smartassistant.router.strategy;

import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.RouteDecision;
import com.example.smartassistant.router.service.AgentDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent Discovery 路由策略
 * 基于 Nacos 元数据的特征匹配
 */
@Component
public class DiscoveryRoutingStrategy implements RoutingStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(DiscoveryRoutingStrategy.class);
    
    private final AgentDiscoveryService agentDiscoveryService;
    
    public DiscoveryRoutingStrategy(AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }
    
    @Override
    public RouteDecision route(String userInput, Map<String, Object> context) {
        log.debug("[DiscoveryRouting] 执行 Agent Discovery 路由");
        
        try {
            // 使用 Agent Discovery 服务进行匹配
            DiscoveredAgent discoveredAgent = agentDiscoveryService.matchAgent(userInput);
            
            if (discoveredAgent != null) {
                log.info("[DiscoveryRouting] Agent Discovery 匹配成功: {} -> {}",
                        discoveredAgent.getServiceName(), discoveredAgent.getUrl());
                
                RouteDecision decision = new RouteDecision();
                decision.setAgentName(discoveredAgent.getAgentName());
                decision.setConfidence(0.7); // Discovery 匹配的置信度
                decision.setRoutingMethod("DISCOVERY_ROUTING");
                
                return decision;
            }
            
            log.warn("[DiscoveryRouting] Agent Discovery 未找到匹配的 Agent");
            return null;
            
        } catch (Exception e) {
            log.error("[DiscoveryRouting] Agent Discovery 路由失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public String getStrategyName() {
        return "DISCOVERY_ROUTING";
    }
    
    @Override
    public int getPriority() {
        return 2; // 中等优先级
    }
}
