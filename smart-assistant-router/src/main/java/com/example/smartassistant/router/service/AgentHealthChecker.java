package com.example.smartassistant.router.service;

import com.example.smartassistant.router.model.DiscoveredAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 健康检查服务 - 主动检查 Agent 的健康状态
 */
@Service
public class AgentHealthChecker {
    
    private static final Logger log = LoggerFactory.getLogger(AgentHealthChecker.class);
    
    private final AgentDiscoveryService agentDiscoveryService;
    
    // ⭐ 记录不健康的 Agent
    private final ConcurrentHashMap<String, Integer> unhealthyAgents = new ConcurrentHashMap<>();
    
    public AgentHealthChecker(AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }
    
    /**
     * ⭐ 定时健康检查(每30秒)
     */
    @Scheduled(fixedDelayString = "${router.agent-discovery.health-check-interval:30000}")
    public void performHealthCheck() {
        Collection<DiscoveredAgent> agents = agentDiscoveryService.getCachedAgents();
        
        if (agents.isEmpty()) {
            log.debug("[HealthChecker] 没有可检查的 Agent");
            return;
        }
        
        log.debug("[HealthChecker] 🔍 开始健康检查,共 {} 个 Agent", agents.size());
        
        int healthyCount = 0;
        int unhealthyCount = 0;
        
        for (DiscoveredAgent agent : agents) {
            boolean isHealthy = checkAgentHealth(agent);
            
            if (isHealthy) {
                healthyCount++;
                // 恢复健康,清除不健康计数
                unhealthyAgents.remove(agent.getServiceName());
            } else {
                unhealthyCount++;
                // 记录不健康次数
                unhealthyAgents.merge(agent.getServiceName(), 1, Integer::sum);
                
                int failCount = unhealthyAgents.get(agent.getServiceName());
                log.warn("[HealthChecker] ⚠️ Agent {} 健康检查失败 (连续 {} 次)", 
                    agent.getServiceName(), failCount);
                
                // 连续失败3次,从缓存中移除
                if (failCount >= 3) {
                    log.error("[HealthChecker] ❌ Agent {} 连续3次健康检查失败,从缓存中移除", 
                        agent.getServiceName());
                    agentDiscoveryService.clearCache();
                    unhealthyAgents.remove(agent.getServiceName());
                }
            }
        }
        
        log.info("[HealthChecker] 📊 健康检查结果: 健康={}, 不健康={}", healthyCount, unhealthyCount);
    }
    
    /**
     * 检查单个 Agent 的健康状态
     */
    private boolean checkAgentHealth(DiscoveredAgent agent) {
        try {
            String healthUrl = agent.getUrl().replace("/a2a", "/actuator/health");
            
            URL url = new URL(healthUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            
            boolean isHealthy = (responseCode == 200);
            
            if (!isHealthy) {
                log.warn("[HealthChecker] Agent {} 返回状态码: {}", 
                    agent.getServiceName(), responseCode);
            }
            
            return isHealthy;
            
        } catch (Exception e) {
            log.debug("[HealthChecker] Agent {} 健康检查异常: {}", 
                agent.getServiceName(), e.getMessage());
            return false;
        }
    }
}
