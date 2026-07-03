/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Nacos A2A Registry AgentCard 配置属性。
 * <p>
 * 读取 {@code nacos.a2a.registry.agent-card.*} 配置，用于构建 AgentCard 对象。
 * 各 Agent 模块在 {@code application.yml} 中定义自身的 AgentCard 元数据，
 * 无需修改 Java 代码即可变更 Agent 名称、描述和技能声明。
 * <p>
 * 配置示例（Order 模块）：
 * <pre>{@code
 * nacos:
 *   a2a:
 *     registry:
 *       agent-card:
 *         name: OrderAgent
 *         description: "订单管理智能体"
 *         version: 1.0.0
 *         protocol-version: 0.3.0
 *         preferred-transport: JSONRPC
 *         url: http://localhost:8085/a2a
 *         skills:
 *           - id: create-order
 *             name: 创建订单
 *             description: 根据用户需求创建新的订单
 *             tags: order,create
 *             examples:
 *               - 帮我创建一个订单
 * }</pre>
 */
@ConfigurationProperties(prefix = "nacos.a2a.registry.agent-card")
public class NacosAgentCardProperties {

    /** Agent 名称（如 OrderAgent / ProductAgent / GeneralChat） */
    private String name;

    /** Agent 功能描述 */
    private String description;

    /** AgentCard 版本号 */
    private String version = "1.0.0";

    /** A2A 协议版本 */
    private String protocolVersion = "0.3.0";

    /** 首选传输协议 */
    private String preferredTransport = "JSONRPC";

    /** Agent 服务地址 */
    private String url;

    /** 技能声明列表 */
    private List<Skill> skills = new ArrayList<>();

    // ==================== Getters & Setters ====================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }

    public String getPreferredTransport() { return preferredTransport; }
    public void setPreferredTransport(String preferredTransport) { this.preferredTransport = preferredTransport; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public List<Skill> getSkills() { return skills; }
    public void setSkills(List<Skill> skills) { this.skills = skills; }

    // ==================== 嵌套类型 ====================

    /**
     * 单个技能声明。
     * <p>
     * tags 和 examples 支持两种格式：
     * <ul>
     *   <li>YAML 列表格式：{@code tags: [order, create]}</li>
     *   <li>逗号分隔字符串：{@code tags: order,create}</li>
     * </ul>
     */
    public static class Skill {

        /** 技能唯一标识（如 create-order） */
        private String id;

        /** 技能名称 */
        private String name;

        /** 技能描述 */
        private String description;

        /** 标签列表（用于路由匹配） */
        private List<String> tags = new ArrayList<>();

        /** 示例问法 */
        private List<String> examples = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }

        public List<String> getExamples() { return examples; }
        public void setExamples(List<String> examples) { this.examples = examples; }
    }
}
