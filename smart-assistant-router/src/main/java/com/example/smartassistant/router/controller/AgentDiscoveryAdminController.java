package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 发现管理端点
 */
@RestController
@RequestMapping("/api/admin/agent-discovery")
public class AgentDiscoveryAdminController {
    
    private static final Logger log = LoggerFactory.getLogger(AgentDiscoveryAdminController.class);
    
    private final AgentDiscoveryService agentDiscoveryService;
    
    public AgentDiscoveryAdminController(AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }
    
    /**
     * 获取 Agent 发现状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        
        status.put("discoveredAgents", agentDiscoveryService.getCachedAgents().size());
        status.put("subscribedServices", agentDiscoveryService.getSubscribedServiceCount());
        status.put("agents", agentDiscoveryService.getCachedAgents());
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * 手动触发扫描并订阅新服务
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scanNewServices() {
        log.info("[Admin API] 手动触发 Agent 服务扫描");
        
        int newSubscribed = agentDiscoveryService.scanAndSubscribeNewServices();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("newSubscribedCount", newSubscribed);
        result.put("message", newSubscribed > 0 
            ? String.format("成功订阅 %d 个新服务", newSubscribed)
            : "没有新服务需要订阅");
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 刷新所有 Agent
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        log.info("[Admin API] 手动刷新 Agent 发现");
        
        int newSubscribed = agentDiscoveryService.scanAndSubscribeNewServices();
        var agents = agentDiscoveryService.discoverAllAgents();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("newSubscribedCount", newSubscribed);
        result.put("totalAgents", agents.size());
        result.put("message", String.format("刷新完成: %d 个 Agent, 新订阅 %d 个服务", 
            agents.size(), newSubscribed));
        
        return ResponseEntity.ok(result);
    }
}
