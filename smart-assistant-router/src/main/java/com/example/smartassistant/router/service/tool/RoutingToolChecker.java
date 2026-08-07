package com.example.smartassistant.router.service.tool;

import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 路由级工具健康检查服务。
 * <p>
 * 在 Router 决定路由到某个 Agent 之前，读取服务发现中的真实实例状态与
 * Agent 自声明能力清单，避免用 Router 进程内的工具注册表推断远端能力。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class RoutingToolChecker {

    private final AgentDiscoveryService agentDiscoveryService;

    public RoutingToolChecker(AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }

    /**
     * 检查目标 Agent 的工具健康状况。
     *
     * @param agentName 目标 Agent 名称
     * @return 检查结果
     */
    public ToolHealthResult checkAgentHealth(String agentName) {
        if (agentName == null || agentName.startsWith("builtin")) {
            return ToolHealthResult.healthy("builtin", "无 Agent 依赖的内置处理");
        }

        DiscoveredAgent agent = agentDiscoveryService.resolveAgent(agentName);
        if (agent == null) {
            return ToolHealthResult.unhealthy(agentName,
                    "未发现 Agent 实例，无法读取实际能力清单", List.of("agent_not_discovered"));
        }
        if (!Boolean.TRUE.equals(agent.getHealthy())) {
            return ToolHealthResult.unhealthy(agentName,
                    "Agent 实例当前不健康", List.of("agent_unhealthy"));
        }

        List<String> capabilities = getCapabilities(agent);
        if (capabilities.isEmpty()) {
            return ToolHealthResult.unhealthy(agentName,
                    "Agent 未声明实际能力清单", List.of("capabilities_not_declared"));
        }

        return ToolHealthResult.healthy(agentName,
                "Agent 实际能力已就绪: " + String.join(", ", capabilities));
    }

    /**
     * 工具健康检查结果。
     */
    public static class ToolHealthResult {
        private final String agentName;
        private final boolean healthy;
        private final String message;
        private final List<String> unhealthyTools;

        private ToolHealthResult(String agentName, boolean healthy, String message,
                                 List<String> unhealthyTools) {
            this.agentName = agentName;
            this.healthy = healthy;
            this.message = message;
            this.unhealthyTools = unhealthyTools;
        }

        public static ToolHealthResult healthy(String agentName, String message) {
            return new ToolHealthResult(agentName, true, message, List.of());
        }

        static ToolHealthResult unhealthy(String agentName, String message,
                                           List<String> unhealthyTools) {
            return new ToolHealthResult(agentName, false, message, unhealthyTools);
        }

        public boolean isHealthy() { return healthy; }
        public String getMessage() { return message; }
        public List<String> getUnhealthyTools() { return unhealthyTools; }
    }

    /**
     * 获取所有 Agent 的工具健康快照（供管理端点使用）。
     */
    public Map<String, Object> getAllAgentsHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> agentNames = new LinkedHashSet<>();
        for (DiscoveredAgent agent : agentDiscoveryService.getCachedAgents()) {
            String agentName = agent.getAgentName() != null ? agent.getAgentName() : agent.getServiceName();
            if (agentName != null && !agentName.isBlank()) agentNames.add(agentName);
        }
        for (String agentName : agentNames) {
            ToolHealthResult health = checkAgentHealth(agentName);
            DiscoveredAgent agent = agentDiscoveryService.resolveAgent(agentName);
            Map<String, Object> agentInfo = new LinkedHashMap<>();
            agentInfo.put("healthy", health.isHealthy());
            agentInfo.put("message", health.getMessage());
            agentInfo.put("capabilities", getCapabilities(agent));
            result.put(agentName, agentInfo);
        }
        // 总览
        long healthyCount = result.values().stream()
                .filter(m -> (boolean) ((Map) m).get("healthy"))
                .count();
        result.put("_summary", Map.of(
                "totalAgents", agentNames.size(),
                "healthyAgents", healthyCount,
                "allHealthy", !agentNames.isEmpty() && healthyCount == agentNames.size()
        ));
        return result;
    }

    private static List<String> getCapabilities(DiscoveredAgent agent) {
        if (agent == null) return List.of();
        AgentMetadata metadata = agent.getMetadata();
        if (metadata == null) return List.of();
        return Arrays.stream(metadata.getCapabilitiesArray())
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }
}
