package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 注册管理控制器
 * 提供 Agent 动态注册、注销、心跳等接口
 */
@RestController
@RequestMapping("/api/router/agents")
@Slf4j
public class AgentRegistrationController {
    
    private final AgentDiscoveryService agentDiscoveryService;
    
    public AgentRegistrationController(AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }
    
    /**
     * ⭐ 获取所有已发现的 Agent
     */
    @GetMapping
    public ResponseEntity<List<DiscoveredAgent>> getAllAgents() {
        List<DiscoveredAgent> agents = agentDiscoveryService.discoverAllAgents();
        return ResponseEntity.ok(agents);
    }
    
    /**
     * ⭐ 获取缓存的 Agent 列表
     */
    @GetMapping("/cached")
    public ResponseEntity<Map<String, DiscoveredAgent>> getCachedAgents() {
        Map<String, DiscoveredAgent> agents = new HashMap<>();
        agentDiscoveryService.getCachedAgents().forEach(agent -> 
            agents.put(agent.getServiceName(), agent)
        );
        return ResponseEntity.ok(agents);
    }

    
    /**
     * ⭐ Agent 发送心跳
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> sendHeartbeat(@RequestBody HeartbeatRequest request) {
        log.debug("[AgentRegistry] 💓 收到心跳: {}", request.getServiceName());
        
        // 更新最后心跳时间
        boolean updated = agentDiscoveryService.updateHeartbeat(request.getServiceName());
        if (!updated) {
            log.warn("[AgentRegistry] ⚠️ 未知 Agent 心跳: {}", request.getServiceName());
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", updated);
        response.put("message", updated ? "心跳接收成功" : "未知 Agent，心跳未记录");
        response.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * ⭐ 清除缓存并重新发现
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshAgents() {
        log.info("[AgentRegistry] 🔄 刷新 Agent 缓存");
        
        agentDiscoveryService.clearCache();
        List<DiscoveredAgent> agents = agentDiscoveryService.discoverAllAgents();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Agent 缓存已刷新");
        response.put("agentCount", agents.size());
        response.put("agents", agents);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * ⭐ 获取指定 Agent 详情
     */
    @GetMapping("/{serviceName}")
    public ResponseEntity<DiscoveredAgent> getAgentDetail(@PathVariable String serviceName) {
        DiscoveredAgent agent = agentDiscoveryService.discoverAgent(serviceName);
        
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(agent);
    }
    
    // ⭐ 请求 DTO
    
    @Data
    public static class RegisterRequest {
        private String serviceName;
        private String agentName;
        private String ip;
        private Integer port;
        private String version;
        private String protocolVersion;
        private Map<String, String> metadata;
    }
    
    @Data
    public static class HeartbeatRequest {
        private String serviceName;
        private Long timestamp;
        private Map<String, Object> metrics;
    }
}
