package com.example.smartassistant.router.scheduler;

import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Router Agent 刷新调度器
 * 定期从 Nacos 重新发现 Agent 并更新 Router Agent 的 Instruction
 */
@Component
public class RouterAgentRefreshScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(RouterAgentRefreshScheduler.class);

    @Value("${router.agent.refresh.enabled:true}")
    private boolean refreshEnabled;
    
    @Value("${router.agent.refresh.interval:300000}") // 默认 5 分钟
    private long refreshInterval;

    private final AgentDiscoveryService agentDiscoveryService;
    
    public RouterAgentRefreshScheduler(
            AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }
    
    /**
     * 定期刷新 Router Agent
     * 从 Nacos 重新发现 Agent 列表并更新 Instruction
     */
    @Scheduled(fixedDelayString = "${router.agent.refresh.interval:300000}")
    public void refreshRouterAgent() {
        if (!refreshEnabled) {
            log.debug("[RouterAgentRefresh] 刷新已禁用");
            return;
        }
        
        try {
            log.info("[RouterAgentRefresh] 开始刷新 Router Agent...");
            
            // ⭐ 定期扫描并订阅新服务
            rescanAndSubscribeNewServices();
            
            // 触发 RouterAgentConfig 重新构建 Bean
            // 由于使用了 @RefreshScope，Spring 会自动重建 Bean
            // 这里只需要记录日志即可
            
            log.info("[RouterAgentRefresh] Router Agent 刷新完成，间隔: {}ms", refreshInterval);
            
        } catch (Exception e) {
            log.error("[RouterAgentRefresh] Router Agent 刷新失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * ⭐ 定期扫描并订阅新服务（解决启动时服务未注册的问题）
     */
    private void rescanAndSubscribeNewServices() {
        try {
            // ⭐ 调用 AgentDiscoveryService 的新方法，自动扫描并订阅新服务
            int newSubscribed = agentDiscoveryService.scanAndSubscribeNewServices();
            
            if (newSubscribed > 0) {
                log.info("[RouterAgentRefresh] ✅ 发现并订阅 {} 个新 Agent 服务", newSubscribed);
            } else {
                log.debug("[RouterAgentRefresh] 🔍 没有新服务需要订阅");
            }
            
        } catch (Exception e) {
            log.warn("[RouterAgentRefresh] ⚠️ 定期扫描失败: {}", e.getMessage());
        }
    }
    
    /**
     * 手动触发刷新（可通过 Actuator Endpoint 调用）
     */
    public void manualRefresh() {
        log.info("[RouterAgentRefresh] 手动触发 Router Agent 刷新");
        refreshRouterAgent();
    }
}
