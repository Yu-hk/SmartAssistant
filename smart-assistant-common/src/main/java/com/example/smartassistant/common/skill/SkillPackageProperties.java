/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

/**
 * 从 YAML/配置属性加载技能包定义。
 *
 * <p>允许通过 application.yml 声明技能包，无需代码注册。
 * 技能包定义格式：
 * <pre>{@code
 * skill-package:
 *   packages:
 *     - id: order-query
 *       name: 订单查询
 *       description: 查询订单状态、物流信息的技能
 *       version: "1.0.0"
 *       tags: [order, query]
 *       instruction: |
 *         当用户查询订单时，优先使用 queryOrder 工具...
 *       bind-agents: [order]
 *     - id: ... 更多技能包
 * }</pre>
 */
@ConfigurationProperties(prefix = "skill-package")
public class SkillPackageProperties {

    private static final Logger log = LoggerFactory.getLogger(SkillPackageProperties.class);

    private List<PackageDef> packages = new ArrayList<>();

    public List<PackageDef> getPackages() { return packages; }
    public void setPackages(List<PackageDef> packages) { this.packages = packages; }

    /**
     * 将属性定义转换为 SkillPackage 并注册到管理器。
     */
    public void registerTo(SkillPackageManager manager) {
        int count = 0;
        for (PackageDef def : packages) {
            try {
                SkillPackage pkg = def.toSkillPackage();
                pkg.setEnabled(def.enabled);
                manager.register(pkg);

                // 绑定到指定 Agent
                if (def.bindAgents != null) {
                    for (String agentId : def.bindAgents) {
                        manager.bind(pkg.getId(), agentId.trim());
                    }
                }
                count++;
            } catch (Exception e) {
                log.warn("[SkillPackage] 加载技能包失败: id={}, error={}", def.id, e.getMessage());
            }
        }
        log.info("[SkillPackage] 从配置加载 {} 个技能包", count);
    }

    public static class PackageDef {
        private String id;
        private String name;
        private String description = "";
        private String version = "1.0.0";
        private String instruction = "";
        private List<String> tags = new ArrayList<>();
        private List<String> bindAgents = new ArrayList<>();
        private List<String> triggerOperations = new ArrayList<>();
        private List<String> triggerTags = new ArrayList<>();
        private List<String> matchTerms = new ArrayList<>();
        private List<String> requiredTools = new ArrayList<>();
        private List<String> dependencies = new ArrayList<>();
        private int priority;
        private boolean alwaysApply;
        private boolean enabled = true;

        // Getters & Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getInstruction() { return instruction; }
        public void setInstruction(String instruction) { this.instruction = instruction; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public List<String> getBindAgents() { return bindAgents; }
        public void setBindAgents(List<String> bindAgents) { this.bindAgents = bindAgents; }
        public List<String> getTriggerOperations() { return triggerOperations; }
        public void setTriggerOperations(List<String> triggerOperations) { this.triggerOperations = triggerOperations; }
        public List<String> getTriggerTags() { return triggerTags; }
        public void setTriggerTags(List<String> triggerTags) { this.triggerTags = triggerTags; }
        public List<String> getMatchTerms() { return matchTerms; }
        public void setMatchTerms(List<String> matchTerms) { this.matchTerms = matchTerms; }
        public List<String> getRequiredTools() { return requiredTools; }
        public void setRequiredTools(List<String> requiredTools) { this.requiredTools = requiredTools; }
        public List<String> getDependencies() { return dependencies; }
        public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public boolean isAlwaysApply() { return alwaysApply; }
        public void setAlwaysApply(boolean alwaysApply) { this.alwaysApply = alwaysApply; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public SkillPackage toSkillPackage() {
            SkillPackage.Builder builder = SkillPackage.builder(id, name)
                    .description(description)
                    .version(version)
                    .instruction(instruction)
                    .priority(priority)
                    .alwaysApply(alwaysApply);
            if (tags != null) tags.forEach(builder::addTag);
            if (triggerOperations != null) triggerOperations.forEach(builder::addTriggerOperation);
            if (triggerTags != null) triggerTags.forEach(builder::addTriggerTag);
            if (matchTerms != null) matchTerms.forEach(builder::addMatchTerm);
            if (requiredTools != null) requiredTools.forEach(builder::addRequiredTool);
            if (dependencies != null) dependencies.forEach(builder::addDependency);
            return builder.build();
        }
    }
}
