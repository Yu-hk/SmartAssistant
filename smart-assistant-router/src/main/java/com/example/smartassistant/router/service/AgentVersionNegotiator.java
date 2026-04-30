package com.example.smartassistant.router.service;

import com.example.smartassistant.router.model.DiscoveredAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 版本协商服务
 * 负责选择兼容的 Agent 版本
 */
@Service
@Slf4j
public class AgentVersionNegotiator {
    
    private final AgentDiscoveryService agentDiscoveryService;
    
    public AgentVersionNegotiator(AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }
    
    /**
     * ⭐ 选择兼容的 Agent 版本
     * 
     * @param agentName Agent 名称
     * @param clientVersion 客户端版本
     * @param protocolVersion 协议版本
     * @return 最合适的 Agent 实例
     */
    public DiscoveredAgent selectCompatibleAgent(
            String agentName, 
            String clientVersion,
            String protocolVersion) {
        
        log.debug("[VersionNegotiator] 寻找兼容的 Agent: {}, 客户端版本: {}, 协议: {}", 
            agentName, clientVersion, protocolVersion);
        
        // 获取所有同名 Agent 实例
        List<DiscoveredAgent> agents = agentDiscoveryService.getCachedAgents().stream()
            .filter(agent -> agent.getServiceName().equals(agentName) || 
                           agent.getAgentName().equals(agentName))
            .collect(Collectors.toList());
        
        if (agents.isEmpty()) {
            log.warn("[VersionNegotiator] 未找到 Agent: {}", agentName);
            return null;
        }
        
        // 过滤出兼容的 Agent
        List<DiscoveredAgent> compatibleAgents = agents.stream()
            .filter(agent -> isVersionCompatible(agent, clientVersion, protocolVersion))
            .collect(Collectors.toList());
        
        if (compatibleAgents.isEmpty()) {
            log.error("[VersionNegotiator] ❌ 没有兼容的 Agent 版本: {}, 客户端: {}, 协议: {}", 
                agentName, clientVersion, protocolVersion);
            return null;
        }
        
        // 选择最高版本的 Agent
        DiscoveredAgent selected = compatibleAgents.stream()
            .max(Comparator.comparing(this::parseVersion))
            .orElse(null);
        
        if (selected != null) {
            log.info("[VersionNegotiator] ✅ 选择 Agent: {}, 版本: {}", 
                selected.getServiceName(), 
                selected.getMetadata() != null ? selected.getMetadata().getVersion() : "unknown");
        }
        
        return selected;
    }
    
    /**
     * ⭐ 检查 Agent 是否兼容
     */
    private boolean isVersionCompatible(
            DiscoveredAgent agent, 
            String clientVersion, 
            String protocolVersion) {
        
        if (agent.getMetadata() == null) {
            log.warn("[VersionNegotiator] Agent {} 缺少元数据", agent.getServiceName());
            return true; // 没有元数据则默认兼容
        }
        
        // 1. 检查客户端版本兼容性
        boolean clientCompatible = agent.getMetadata().isClientVersionCompatible(clientVersion);
        if (!clientCompatible) {
            log.debug("[VersionNegotiator] ❌ 客户端版本不兼容: Agent={}, 要求>={}, 当前={}",
                agent.getServiceName(),
                agent.getMetadata().getMinClientVersion(),
                clientVersion);
            return false;
        }
        
        // 2. 检查协议兼容性
        if (protocolVersion != null && !protocolVersion.isEmpty()) {
            boolean protocolSupported = agent.getMetadata().supportsProtocol(protocolVersion);
            if (!protocolSupported) {
                log.debug("[VersionNegotiator] ❌ 协议不兼容: Agent={}, 支持={}, 请求={}",
                    agent.getServiceName(),
                    agent.getMetadata().getSupportedProtocols(),
                    protocolVersion);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 解析版本号用于比较
     */
    private double parseVersion(DiscoveredAgent agent) {
        if (agent.getMetadata() == null || agent.getMetadata().getVersion() == null) {
            return 0.0;
        }
        
        try {
            String version = agent.getMetadata().getVersion();
            String[] parts = version.split("\\.");
            
            double result = 0.0;
            for (int i = 0; i < parts.length && i < 3; i++) {
                result += Integer.parseInt(parts[i]) * Math.pow(1000, 2 - i);
            }
            return result;
        } catch (NumberFormatException e) {
            log.warn("[VersionNegotiator] 无法解析版本号: {}", 
                agent.getMetadata().getVersion());
            return 0.0;
        }
    }
}
