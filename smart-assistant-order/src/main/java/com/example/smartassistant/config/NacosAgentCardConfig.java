/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Properties;

/**
 * Nacos A2A Registry 配置 — 以 Order Agent 身份注册 AgentCard。
 * <p>
 * 在现有 Nacos Naming Service 注册基础上，额外向 Nacos A2A Registry
 * 发布结构化 AgentCard（含技能声明、能力描述、版本号），
 * 使 Router 可通过 {@code AiService.subscribeAgentCard()} 获取
 * 更丰富的 Agent 元数据。
 * <p>
 * 与 Nacos Naming 的关系：
 * <ul>
 *   <li>Naming Service → 服务实例发现（IP:Port）</li>
 *   <li>A2A Registry → Agent 元数据发现（skills/description/version）</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(name = "nacos.a2a.registry.enabled", havingValue = "true", matchIfMissing = false)
public class NacosAgentCardConfig {

    private static final Logger log = LoggerFactory.getLogger(NacosAgentCardConfig.class);

    @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${spring.cloud.nacos.discovery.username:${NACOS_USERNAME:nacos}}")
    private String username;

    @Value("${spring.cloud.nacos.discovery.password:${NACOS_PASSWORD:nacos123}}")
    private String password;

    @Value("${spring.cloud.nacos.discovery.namespace:}")
    private String namespace;

    @Value("${server.port:8085}")
    private int serverPort;

    /**
     * 构建 Order Agent 的 AgentCard。
     * <p>
     * AgentCard 中声明的 skills 与 Order 模块现有的意图检测对齐：
     * CREATE_ORDER / QUERY_ORDER / REFUND / CANCEL。
     */
    @Bean
    public AgentCard orderAgentCard() {
        AgentCard card = new AgentCard();
        card.setName("OrderAgent");
        card.setDescription("订单管理智能体，支持创建订单、查询订单、退款处理和取消操作");
        card.setUrl("http://localhost:" + serverPort + "/a2a");
        card.setVersion("1.0.0");
        card.setProtocolVersion("0.3.0");
        card.setPreferredTransport("JSONRPC");

        // 声明技能 —— 与 OrderIntentService 中 CREATE_ORDER/QUERY_ORDER/REFUND/CANCEL 对齐
        card.setSkills(List.of(
            buildSkill("create-order", "创建订单", "根据用户需求创建新的订单",
                List.of("order", "create"),
                List.of("帮我创建一个订单", "帮我下单")),
            buildSkill("query-order", "查询订单", "根据订单号或用户信息查询订单状态",
                List.of("order", "query"),
                List.of("查询订单12345的状态", "我的订单有哪些")),
            buildSkill("refund-order", "退款处理", "处理订单退款申请",
                List.of("order", "refund"),
                List.of("我要退款", "申请退款")),
            buildSkill("cancel-order", "取消订单", "取消未发货的订单",
                List.of("order", "cancel"),
                List.of("取消订单", "我不想要了"))
        ));

        return card;
    }

    /**
     * 创建 Nacos A2A Registry 的 AiService（gRPC 长连接）。
     * <p>
     * 与 NacosConfig 中的 NamingService 使用相同的 Nacos 连接参数，
     * 共享同一 Nacos 集群。AiService 负责 AgentCard 的注册/发现/订阅。
     */
    @Bean(destroyMethod = "shutdown")
    public AiService aiService() throws NacosException {
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        props.setProperty(PropertyKeyConst.USERNAME, username);
        props.setProperty(PropertyKeyConst.PASSWORD, password);
        if (namespace != null && !namespace.isEmpty()) {
            props.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        }
        AiService service = AiFactory.createAiService(props);
        log.info("[NacosA2A] AiService 已创建（gRPC 连接）");
        return service;
    }

    // ==================== 内部辅助方法 ====================

    private AgentSkill buildSkill(String id, String name, String description,
                                   List<String> tags, List<String> examples) {
        AgentSkill skill = new AgentSkill();
        skill.setId(id);
        skill.setName(name);
        skill.setDescription(description);
        skill.setTags(tags);
        skill.setExamples(examples);
        return skill;
    }
}
