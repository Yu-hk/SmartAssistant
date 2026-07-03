/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.agent;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosAgentCardListener;
import com.alibaba.nacos.api.ai.listener.NacosAgentCardEvent;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import com.example.smartassistant.router.model.DiscoveredAgent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Nacos A2A Registry AgentCard 元数据提供者。
 * <p>
 * 通过 AiService 订阅 AgentCard 变更，获取各 Agent 的结构化元数据
 * （skills/description/version/capabilities），为现有
 * {@link AgentDiscoveryService} 补充 enriched metadata。
 * <p>
 * 订阅的 Agent 列表从 {@link AgentDiscoveryService#getCachedAgents()} 动态获取，
 * 自动覆盖所有已发现的 Agent。若 AgentDiscoveryService 尚未完成初始化，则降级输出
 * 日志警告，待后续定时刷新时自动触发重新订阅。
 * <p>
 * 与 AgentDiscoveryService 的分工：
 * <ul>
 *   <li>AgentDiscoveryService → 服务实例级发现（基于 NamingService，关注 IP:Port 和实例健康）</li>
 *   <li>A2aAgentMetadataProvider → Agent 元数据级发现（基于 AiService，关注 skills/版本/能力声明）</li>
 * </ul>
 * <p>
 * 此服务受 {@code nacos.a2a.discovery.enabled=true} 开关控制，默认不启用。
 *
 * @since 1.0.0
 */
@Service
@ConditionalOnBean(AiService.class)
public class A2aAgentMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(A2aAgentMetadataProvider.class);

    private final AiService aiService;
    private final AgentDiscoveryService agentDiscoveryService;

    /** AgentCard 缓存：agentName -> AgentCard */
    private final Map<String, AgentCard> agentCardCache = new ConcurrentHashMap<>();

    /** 已订阅的 Agent 集合，防止重复订阅 */
    private final Set<String> subscribedAgents = ConcurrentHashMap.newKeySet();

    public A2aAgentMetadataProvider(AiService aiService,
                                     AgentDiscoveryService agentDiscoveryService) {
        this.aiService = aiService;
        this.agentDiscoveryService = agentDiscoveryService;
    }

    @PostConstruct
    public void init() {
        log.info("[A2AMetadata] 启动 A2A AgentCard 元数据订阅...");

        // 从 AgentDiscoveryService 获取已发现的 Agent 列表，动态订阅它们的 AgentCard
        Collection<DiscoveredAgent> agents = agentDiscoveryService.getCachedAgents();

        if (agents.isEmpty()) {
            log.warn("[A2AMetadata] AgentDiscoveryService 尚无已发现的 Agent，" +
                     "订阅推迟到定时刷新时自动触发");
        } else {
            agents.forEach(agent -> subscribeDerived(agent));
        }

        log.info("[A2AMetadata] AgentCard 订阅已启动，当前订阅 {} 个 Agent，缓存 {} 个 AgentCard",
            subscribedAgents.size(), agentCardCache.size());
    }

    /**
     * 根据 AgentDiscoveryService 发现的 Agent 订阅其 A2A AgentCard。
     * <p>
     * 从 {@link DiscoveredAgent#getAgentName()} 获取名称（如 {@code order_agent}），
     * 通过命名约定转换为 A2A Registry 中的 AgentCard 名称（如 {@code OrderAgent}）。
     * <p>
     * 命名约定：snake_case → PascalCase
     * <ul>
     *   <li>{@code order_agent} → {@code OrderAgent}</li>
     *   <li>{@code product_agent} → {@code ProductAgent}</li>
     *   <li>{@code general_agent} → {@code GeneralAgent}</li>
     * </ul>
     *
     * @param agent AgentDiscoveryService 发现的 Agent
     */
    private void subscribeDerived(DiscoveredAgent agent) {
        String rawName = agent.getAgentName();
        if (rawName == null || rawName.isEmpty()) {
            return;
        }
        // snake_case → PascalCase
        String a2aName = toPascalCase(rawName);
        subscribe(a2aName);
    }

    /**
     * 订阅指定 Agent 的 AgentCard 变更（幂等，重复调用自动跳过）。
     * <p>
     * subscribeAgentCard 方法在注册监听器的同时返回当前最新 AgentCard，
     * 后续 Nacos 推送变更时自动更新缓存。
     */
    private void subscribe(String agentName) {
        if (!subscribedAgents.add(agentName)) {
            log.debug("[A2AMetadata] Agent [{}] 已订阅，跳过", agentName);
            return;
        }

        try {
            AgentCardDetailInfo initial = aiService.subscribeAgentCard(
                agentName, new AbstractNacosAgentCardListener() {
                    @Override
                    public void onEvent(NacosAgentCardEvent event) {
                        AgentCardDetailInfo updated = event.getAgentCard();
                        if (updated != null) {
                            agentCardCache.put(agentName, updated);
                            log.info("[A2AMetadata] AgentCard 更新: name={}, version={}, skills={}",
                                agentName, updated.getVersion(),
                                updated.getSkills() != null ? updated.getSkills().size() : 0);
                        } else {
                            agentCardCache.remove(agentName);
                            log.warn("[A2AMetadata] AgentCard 被移除: name={}", agentName);
                        }
                    }
                });

            if (initial != null) {
                agentCardCache.put(agentName, initial);
                log.info("[A2AMetadata] 初始加载 AgentCard: name={}, version={}, skills={}",
                    agentName, initial.getVersion(),
                    initial.getSkills() != null ? initial.getSkills().size() : 0);
            } else {
                log.warn("[A2AMetadata] Agent [{}] 尚未注册 AgentCard，" +
                         "Agent 可能尚未完成 A2A 注册或未启用 A2A Registry", agentName);
            }
        } catch (Exception e) {
            log.warn("[A2AMetadata] 订阅 Agent [{}] 失败: {}",
                agentName, e.getMessage());
        }
    }

    /**
     * 扫描 AgentDiscoveryService 中的新 Agent 并订阅其 A2A AgentCard。
     * <p>
     * 由路由器刷新调度器定时调用，覆盖 Agent 动态上下线场景。
     *
     * @return 本次新订阅的 Agent 数量
     */
    public int scanAndSubscribeNewAgents() {
        Collection<DiscoveredAgent> agents = agentDiscoveryService.getCachedAgents();
        int count = 0;
        for (DiscoveredAgent agent : agents) {
            String rawName = agent.getAgentName();
            if (rawName == null) continue;
            String a2aName = toPascalCase(rawName);
            // subscribe 内部通过 subscribedAgents 做幂等判断
            int oldSize = subscribedAgents.size();
            subscribe(a2aName);
            if (subscribedAgents.size() > oldSize) {
                count++;
            }
        }
        if (count > 0) {
            log.info("[A2AMetadata] 本次扫描发现 {} 个新的 Agent 并完成订阅", count);
        }
        return count;
    }

    // ==================== 查询 API ====================

    /**
     * 获取 Agent 的 AgentCard（结构化元数据）。
     *
     * @param agentName Agent 名称（如 OrderAgent）
     * @return AgentCard，如未订阅或尚未加载则返回 null
     */
    public AgentCard getAgentCard(String agentName) {
        return agentCardCache.get(agentName);
    }

    /**
     * 获取已订阅的所有 AgentCard。
     */
    public Map<String, AgentCard> getAllAgentCards() {
        return Map.copyOf(agentCardCache);
    }

    /**
     * 获取 Agent 声明的 skills 列表。
     */
    public List<AgentSkill> getAgentSkills(String agentName) {
        AgentCard card = agentCardCache.get(agentName);
        return card != null ? card.getSkills() : Collections.emptyList();
    }

    // ==================== 命名转换 ====================

    /**
     * snake_case → PascalCase。
     * <ul>
     *   <li>{@code order_agent} → {@code OrderAgent}</li>
     *   <li>{@code product} → {@code Product}</li>
     *   <li>{@code general_agent} → {@code GeneralAgent}</li>
     * </ul>
     */
    static String toPascalCase(String snakeCase) {
        if (snakeCase == null || snakeCase.isEmpty()) {
            return snakeCase;
        }
        return Arrays.stream(snakeCase.split("_"))
            .filter(part -> !part.isEmpty())
            .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1).toLowerCase())
            .collect(Collectors.joining());
    }

    // ==================== 生命周期 ====================

    /**
     * 清理订阅（应用关闭时）。
     */
    @PreDestroy
    public void destroy() {
        for (String name : agentCardCache.keySet()) {
            try {
                aiService.unsubscribeAgentCard(name, null);
                log.info("[A2AMetadata] 取消订阅: name={}", name);
            } catch (Exception e) {
                log.warn("[A2AMetadata] 取消订阅失败: name={}", name, e);
            }
        }
        agentCardCache.clear();
        subscribedAgents.clear();
    }
}
