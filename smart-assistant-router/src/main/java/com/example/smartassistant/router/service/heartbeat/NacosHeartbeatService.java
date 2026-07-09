/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.heartbeat;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 基于 Nacos 的服务级心跳与健康检测。
 *
 * <p>利用 Nacos 内置心跳机制（每 5 秒自动上报）和 {@link NamingService#selectInstances(String, boolean)}
 * API 判断服务实例是否存活。替代原 Redis Heartbeat 中的服务级健康检查。</p>
 *
 * <p>与 {@link AgentHeartbeatService} 的分工：</p>
 * <ul>
 *   <li><b>NacosHeartbeatService</b> — 服务实例级健康（is the service alive?）</li>
 *   <li><b>AgentHeartbeatService (Redis)</b> — 任务执行级进度（is this graph node making progress?）</li>
 * </ul>
 */
@Service
public class NacosHeartbeatService {

    private static final Logger LOG = LoggerFactory.getLogger(NacosHeartbeatService.class);

    private final NamingService namingService;
    private final Set<String> knownServices = new CopyOnWriteArraySet<>();

    public NacosHeartbeatService(NamingService namingService) {
        this.namingService = namingService;
    }

    /**
     * 检查指定服务是否有健康的实例在线。
     *
     * @param serviceName Nacos 服务名
     * @return true 至少有一个健康实例
     */
    public boolean isServiceHealthy(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return false;
        try {
            List<Instance> instances = namingService.selectInstances(serviceName, true);
            boolean healthy = instances != null && !instances.isEmpty();
            if (!healthy) {
                LOG.warn("[NacosHeartbeat] 服务不健康: service={}", serviceName);
            }
            return healthy;
        } catch (Exception e) {
            LOG.warn("[NacosHeartbeat] 健康检查异常: service={}, error={}", serviceName, e.getMessage());
            return false;
        }
    }

    /**
     * 检查指定服务的健康实例数量。
     */
    public int healthyInstanceCount(String serviceName) {
        try {
            List<Instance> instances = namingService.selectInstances(serviceName, true);
            return instances != null ? instances.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取所有健康实例的元数据。
     */
    public List<Instance> getHealthyInstances(String serviceName) {
        try {
            return namingService.selectInstances(serviceName, true);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 检查一组服务中是否有任何服务健康。
     */
    public boolean anyServiceHealthy(List<String> serviceNames) {
        return serviceNames.stream().anyMatch(this::isServiceHealthy);
    }

    /**
     * 注册已知服务名（便于批量健康检查）。
     */
    public void registerService(String serviceName) {
        if (serviceName != null && !serviceName.isBlank()) {
            knownServices.add(serviceName);
        }
    }

    /**
     * 批量健康检查：返回所有不健康的服务。
     */
    public List<String> findUnhealthyServices() {
        return knownServices.stream()
                .filter(s -> !isServiceHealthy(s))
                .toList();
    }

    /**
     * 获取服务状态摘要。
     */
    public String getStatusSummary() {
        StringBuilder sb = new StringBuilder("【Agent 服务健康状态】\n");
        if (knownServices.isEmpty()) {
            sb.append("  (无已注册服务)\n");
            return sb.toString();
        }
        for (String service : knownServices) {
            boolean healthy = isServiceHealthy(service);
            int count = healthyInstanceCount(service);
            sb.append("  - ").append(service).append(": ")
                    .append(healthy ? "🟢 健康" : "🔴 离线")
                    .append(" (").append(count).append(" 实例)\n");
        }
        return sb.toString();
    }
}
