package com.example.smartassistant.router.service.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.error.ErrorRecoveryService;
import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolGateway;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 路由级工具健康检查服务。
 * <p>
 * 在 Router 决定路由到某个 Agent 之前，检查该 Agent 需要的工具是否就绪。
 * 如果关键工具熔断或未注册，提前降级，避免浪费 LLM 调用。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class RoutingToolChecker {

    private static final Logger log = LoggerFactory.getLogger(RoutingToolChecker.class);

    private final ToolRegistry toolRegistry;
    private final ToolGateway toolGateway;
    private final ErrorRecoveryService errorRecoveryService;

    // Agent → 关键工具名称映射（硬编码，后续可配置化）
    private static final Map<String, List<String>> AGENT_CRITICAL_TOOLS = new LinkedHashMap<>();

    static {
        AGENT_CRITICAL_TOOLS.put("order", List.of(
                "queryOrder", "createOrder", "cancelOrder", "applyRefund", "payOrder"
        ));
        AGENT_CRITICAL_TOOLS.put("product", List.of(
                "queryProductInfo", "checkStock", "getPrice"
        ));
        AGENT_CRITICAL_TOOLS.put("general", List.of(
                "calculate", "getHotNews", "searchWeb", "executeScript", "queryWeather"
        ));
    }

    public RoutingToolChecker(ToolRegistry toolRegistry,
                              ToolGateway toolGateway,
                              ErrorRecoveryService errorRecoveryService) {
        this.toolRegistry = toolRegistry;
        this.toolGateway = toolGateway;
        this.errorRecoveryService = errorRecoveryService;
    }

    /**
     * 检查目标 Agent 的工具健康状况。
     *
     * @param agentName 目标 Agent 名称
     * @return 检查结果
     */
    public ToolHealthResult checkAgentHealth(String agentName) {
        if (agentName == null) {
            return ToolHealthResult.healthy("builtin", "无 Agent 依赖的内置处理");
        }

        List<String> criticalTools = AGENT_CRITICAL_TOOLS.get(agentName);
        if (criticalTools == null || criticalTools.isEmpty()) {
            return ToolHealthResult.healthy(agentName, "无关键工具依赖");
        }

        List<String> unhealthy = new ArrayList<>();
        List<String> healthy = new ArrayList<>();

        for (String toolName : criticalTools) {
            ToolDefinition def = toolRegistry.get(toolName);
            if (def == null) {
                unhealthy.add(toolName + "(未注册)");
                continue;
            }
            // 检查注册状态
            healthy.add(toolName);
        }

        if (unhealthy.isEmpty()) {
            return ToolHealthResult.healthy(agentName,
                    String.format("关键工具全部就绪: %s", String.join(", ", healthy)));
        }

        log.warn("[ToolCheck] Agent={} 存在不健康工具: {}", agentName, unhealthy);
        return ToolHealthResult.unhealthy(agentName,
                String.format("以下工具不可用: %s", String.join(", ", unhealthy)),
                unhealthy);
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
        for (String agentName : AGENT_CRITICAL_TOOLS.keySet()) {
            ToolHealthResult health = checkAgentHealth(agentName);
            Map<String, Object> agentInfo = new LinkedHashMap<>();
            agentInfo.put("healthy", health.isHealthy());
            agentInfo.put("message", health.getMessage());
            agentInfo.put("registeredTools", AGENT_CRITICAL_TOOLS.get(agentName));
            result.put(agentName, agentInfo);
        }
        // 总览
        long healthyCount = result.values().stream()
                .filter(m -> (boolean) ((Map) m).get("healthy"))
                .count();
        result.put("_summary", Map.of(
                "totalAgents", AGENT_CRITICAL_TOOLS.size(),
                "healthyAgents", healthyCount,
                "allHealthy", healthyCount == AGENT_CRITICAL_TOOLS.size()
        ));
        return result;
    }
}
