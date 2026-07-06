/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.skill;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 技能包——可复用的 AI 能力单元。
 *
 * <p>参考 Snail AI 的 Skill 系统设计。一个技能包包含：
 * <ul>
 *   <li>SKILL.md 指令文件（定义技能的行为和约束）</li>
 *   <li>可选的支撑文件（参考文档、模板等）</li>
 *   <li>版本信息和元数据</li>
 * </ul>
 *
 * <p>技能包可以绑定到 Agent，注入到 Agent 的 system prompt 中。
 */
public class SkillPackage {

    /** 技能包唯一 ID */
    private final String id;

    /** 技能名称 */
    private final String name;

    /** 技能描述 */
    private final String description;

    /** 版本号 */
    private final String version;

    /** SKILL.md 指令内容 */
    private final String instruction;

    /** 支撑文件（文件名 → 内容） */
    private final Map<String, String> supportingFiles;

    /** 标签 */
    private final Set<String> tags;

    /** 创建时间 */
    private final LocalDateTime createdAt;

    /** 适用 Agent 列表（agentId → 绑定时间） */
    private final Map<String, LocalDateTime> boundAgents;

    /** 是否启用 */
    private boolean enabled;

    public SkillPackage(String id, String name, String description,
                        String version, String instruction) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.version = version;
        this.instruction = instruction;
        this.supportingFiles = new LinkedHashMap<>();
        this.tags = new LinkedHashSet<>();
        this.createdAt = LocalDateTime.now();
        this.boundAgents = new LinkedHashMap<>();
        this.enabled = true;
    }

    // ==================== Getters ====================

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public String getInstruction() { return instruction; }
    public Map<String, String> getSupportingFiles() { return Collections.unmodifiableMap(supportingFiles); }
    public Set<String> getTags() { return Collections.unmodifiableSet(tags); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Map<String, LocalDateTime> getBoundAgents() { return Collections.unmodifiableMap(boundAgents); }
    public boolean isEnabled() { return enabled; }

    // ==================== Methods ====================

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public SkillPackage addTag(String tag) {
        this.tags.add(tag);
        return this;
    }

    public SkillPackage addSupportingFile(String fileName, String content) {
        this.supportingFiles.put(fileName, content);
        return this;
    }

    public SkillPackage bindToAgent(String agentId) {
        this.boundAgents.put(agentId, LocalDateTime.now());
        return this;
    }

    public SkillPackage unbindFromAgent(String agentId) {
        this.boundAgents.remove(agentId);
        return this;
    }

    public boolean isBoundTo(String agentId) {
        return boundAgents.containsKey(agentId);
    }

    /**
     * 构建注入 Agent system prompt 的指令片段。
     *
     * @return 指令文本，空字符串表示无指令
     */
    public String buildInjectionPrompt() {
        if (!enabled || instruction == null || instruction.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n【技能包：").append(name).append("】\n");
        sb.append(instruction.trim()).append("\n");

        if (!supportingFiles.isEmpty()) {
            sb.append("\n【参考文件】\n");
            for (String fileName : supportingFiles.keySet()) {
                sb.append("- ").append(fileName).append("\n");
            }
        }

        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SkillPackage that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "SkillPackage{id='" + id + "', name='" + name + "', version='" + version + "'}";
    }

    /**
     * 从 SKILL.md 内容创建技能包构建器。
     */
    public static Builder builder(String id, String name) {
        return new Builder(id, name);
    }

    public static class Builder {
        private final String id;
        private final String name;
        private String description = "";
        private String version = "1.0.0";
        private String instruction = "";
        private final Map<String, String> supportingFiles = new LinkedHashMap<>();
        private final Set<String> tags = new LinkedHashSet<>();

        Builder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public Builder description(String description) { this.description = description; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder instruction(String instruction) { this.instruction = instruction; return this; }
        public Builder addFile(String fileName, String content) { this.supportingFiles.put(fileName, content); return this; }
        public Builder addTag(String tag) { this.tags.add(tag); return this; }

        public SkillPackage build() {
            SkillPackage pkg = new SkillPackage(id, name, description, version, instruction);
            supportingFiles.forEach(pkg::addSupportingFile);
            tags.forEach(pkg::addTag);
            return pkg;
        }
    }
}
