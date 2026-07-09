/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.heartbeat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 双层心跳监控器。
 *
 * <p>整合 Nacos 服务级心跳 + Redis 任务级心跳：</p>
 * <ul>
 *   <li><b>服务级</b>（Nacos）：Agent 实例是否存活？→ {@link #checkServiceHealth(String)}</li>
 *   <li><b>任务级</b>（Redis）：Graph 节点是否按预期执行？→ {@link #checkTaskTimeout(String, String, long)}</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 *   // 先检查服务是否存活（Nacos）
 *   if (!monitor.checkServiceHealth("smart-assistant-order-service")) {
 *       // 服务已下线，触发熔断
 *   }
 *   // 再检查任务是否超时（Redis）
 *   if (monitor.checkTaskTimeout(requestId, agent, 30)) {
 *       // 任务执行超时，触发重试
 *   }
 * }</pre>
 */
@Service
public class HeartbeatMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final AgentHeartbeatService taskHeartbeatService;
    private final NacosHeartbeatService nacosHeartbeatService;

    public HeartbeatMonitor(AgentHeartbeatService taskHeartbeatService,
                            NacosHeartbeatService nacosHeartbeatService) {
        this.taskHeartbeatService = taskHeartbeatService;
        this.nacosHeartbeatService = nacosHeartbeatService;
    }

    // ═══════════════════════════════════════════════════════════
    // 服务级健康检查 — Nacos
    // ═══════════════════════════════════════════════════════════

    /**
     * 检查 Agent 服务是否存活（基于 Nacos 心跳）。
     *
     * @param serviceName Nacos 服务名（如 "smart-assistant-order-service"）
     * @return true 服务有健康实例在线
     */
    public boolean checkServiceHealth(String serviceName) {
        return nacosHeartbeatService.isServiceHealthy(serviceName);
    }

    /**
     * 批量检查服务健康状态。
     */
    public List<String> findUnhealthyServices(List<String> serviceNames) {
        List<String> unhealthy = new ArrayList<>();
        for (String name : serviceNames) {
            if (!nacosHeartbeatService.isServiceHealthy(name)) {
                unhealthy.add(name);
            }
        }
        return unhealthy;
    }

    // ═══════════════════════════════════════════════════════════
    // 任务级超时检测 — Redis
    // ═══════════════════════════════════════════════════════════

    /**
     * 检查单个 Agent 任务是否超时。
     *
     * @param requestId      主任务 ID
     * @param agentName      子 Agent 名称
     * @param timeoutSeconds 超时阈值（秒）
     * @return true 任务超时或无心跳记录
     */
    public boolean checkTaskTimeout(String requestId, String agentName, long timeoutSeconds) {
        return taskHeartbeatService.isTimeout(requestId, agentName, timeoutSeconds);
    }

    /**
     * 批量检查任务超时。
     */
    public List<String> checkTaskTimeouts(String requestId, List<String> expectedAgents, long timeoutSeconds) {
        List<String> timedOut = new ArrayList<>();
        for (String agent : expectedAgents) {
            if (checkTaskTimeout(requestId, agent, timeoutSeconds)) {
                LOG.warn("[HeartbeatMonitor] 任务超时: agent={}, requestId={}", agent, requestId);
                timedOut.add(agent);
            }
        }
        return timedOut;
    }

    /**
     * 获取任务级状态摘要。
     */
    public String getTaskStatusSummary(String requestId) {
        Set<String> agents = taskHeartbeatService.getActiveAgents(requestId);
        if (agents.isEmpty()) {
            return "无活跃任务 Agent";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【任务级状态摘要】\n");
        for (String agent : agents) {
            AgentHeartbeatService.HeartbeatInfo beat = taskHeartbeatService.getLastBeat(requestId, agent);
            if (beat == null) {
                sb.append("- ").append(agent).append(": 无心跳\n");
            } else {
                String statusLabel = switch (beat.status) {
                    case "RUNNING" -> "🔄 运行中";
                    case "COMPLETED" -> "✅ 已完成";
                    case "FAILED" -> "❌ 失败";
                    default -> "❓ " + beat.status;
                };
                sb.append("- ").append(agent).append(": ").append(statusLabel);
                if (beat.progress != null && !beat.progress.isBlank()) {
                    sb.append(" (").append(beat.progress).append(")");
                }
                sb.append(" [").append(beat.elapsedSeconds()).append("s]").append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 获取服务级状态摘要。
     */
    public String getServiceStatusSummary() {
        return nacosHeartbeatService.getStatusSummary();
    }

    /**
     * 等待所有子 Agent 任务完成或超时。
     */
    public int waitForTaskCompletion(String requestId, List<String> expectedAgents,
                                     long timeoutSeconds, long pollIntervalMs) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        int completed = 0;

        while (System.currentTimeMillis() < deadline) {
            completed = 0;
            for (String agent : expectedAgents) {
                AgentHeartbeatService.HeartbeatInfo beat =
                        taskHeartbeatService.getLastBeat(requestId, agent);
                if (beat != null && beat.isCompleted()) {
                    completed++;
                }
            }
            if (completed >= expectedAgents.size()) {
                return completed;
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return completed;
            }
        }

        // 超时：记录未完成
        for (String agent : expectedAgents) {
            AgentHeartbeatService.HeartbeatInfo beat =
                    taskHeartbeatService.getLastBeat(requestId, agent);
            if (beat == null || !beat.isCompleted()) {
                LOG.warn("[HeartbeatMonitor] Agent 超时未完成: agent={}, requestId={}", agent, requestId);
            }
        }
        return completed;
    }

    // ═══════════════════════════════════════════════════════════
    // 完全健康检查 — Nacos + Redis 组合
    // ═══════════════════════════════════════════════════════════

    /**
     * 双层一体的健康检查：先查服务是否存活（Nacos），再查任务是否超时（Redis）。
     *
     * @param requestId      主任务 ID
     * @param serviceName    Nacos 服务名
     * @param agentName      子 Agent 名称
     * @param timeoutSeconds 任务超时阈值（秒）
     * @return 健康检查结果
     */
    public HealthCheckResult checkCombined(String requestId, String serviceName,
                                            String agentName, long timeoutSeconds) {
        boolean serviceHealthy = nacosHeartbeatService.isServiceHealthy(serviceName);
        boolean taskOnTime = !taskHeartbeatService.isTimeout(requestId, agentName, timeoutSeconds);

        if (!serviceHealthy) {
            return new HealthCheckResult(false, "SERVICE_DOWN",
                    "服务 " + serviceName + " 无健康实例");
        }
        if (!taskOnTime) {
            return new HealthCheckResult(false, "TASK_TIMEOUT",
                    "任务 " + agentName + " 执行超时 (" + timeoutSeconds + "s)");
        }
        return new HealthCheckResult(true, "HEALTHY", "");
    }

    /** 健康检查结果 */
    public record HealthCheckResult(
            boolean healthy,
            String reasonCode,
            String detail
    ) {}
}
