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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos A2A Registry AgentCard 元数据提供者。
 * <p>
 * 通过 AiService 订阅 AgentCard 变更，获取各 Agent 的结构化元数据
 * （skills/description/version/capabilities），为现有
 * {@link AgentDiscoveryService} 补充 enriched metadata。
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

    /** AgentCard 缓存：agentName -> AgentCard */
    private final Map<String, AgentCard> agentCardCache = new ConcurrentHashMap<>();

    public A2aAgentMetadataProvider(AiService aiService) {
        this.aiService = aiService;
    }

    @PostConstruct
    public void init() {
        log.info("[A2AMetadata] 启动 A2A AgentCard 元数据订阅...");

        // 订阅关注的 Agent（可在此扩展）
        subscribe("OrderAgent");
        // 后续可增加: subscribe("ProductAgent"); subscribe("GeneralAgent");

        log.info("[A2AMetadata] AgentCard 订阅已启动，当前缓存: {} 个", agentCardCache.size());
    }

    /**
     * 订阅指定 Agent 的 AgentCard 变更。
     * <p>
     * subscribeAgentCard 方法在注册监听器的同时返回当前最新 AgentCard，
     * 后续 Nacos 推送变更时自动更新缓存。
     */
    private void subscribe(String agentName) {
        try {
            // subscribeAgentCard 返回当前的 AgentCardDetailInfo 并注册变更监听
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

            // 订阅时返回的 AgentCard 可以直接缓存
            if (initial != null) {
                agentCardCache.put(agentName, initial);
                log.info("[A2AMetadata] 初始加载 AgentCard: name={}, version={}, skills={}",
                    agentName, initial.getVersion(),
                    initial.getSkills() != null ? initial.getSkills().size() : 0);
            } else {
                log.warn("[A2AMetadata] Agent [{}] 尚未注册 AgentCard", agentName);
            }
        } catch (Exception e) {
            log.warn("[A2AMetadata] 订阅 Agent [{}] 失败: {}", agentName, e.getMessage());
        }
    }

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
    }
}
