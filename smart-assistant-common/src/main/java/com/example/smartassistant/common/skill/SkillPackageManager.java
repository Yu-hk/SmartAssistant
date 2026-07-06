/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能包管理器——注册、查询、绑定技能包。
 *
 * <p>参考 Snail AI 的 Skill 管理机制。
 * 技能包可以在启动时通过代码注册，也可以在运行时动态添加。
 *
 * <p>核心功能：
 * <ul>
 *   <li>注册/注销技能包</li>
 *   <li>按 Agent ID 查询绑定的技能包</li>
 *   <li>构建 Agent 可用的技能注入 prompt</li>
 * </ul>
 */
public class SkillPackageManager {

    private static final Logger log = LoggerFactory.getLogger(SkillPackageManager.class);

    /** 全部已注册技能包：skillId → SkillPackage */
    private final ConcurrentHashMap<String, SkillPackage> skillPackages = new ConcurrentHashMap<>();

    /** Agent → 技能包索引 */
    private final ConcurrentHashMap<String, Set<String>> agentSkillIndex = new ConcurrentHashMap<>();

    // ==================== 注册与注销 ====================

    /**
     * 注册技能包。
     */
    public SkillPackageManager register(SkillPackage skillPackage) {
        Objects.requireNonNull(skillPackage, "skillPackage must not be null");
        skillPackages.put(skillPackage.getId(), skillPackage);

        // 如果技能包已经有绑定关系，重建索引
        for (String agentId : skillPackage.getBoundAgents().keySet()) {
            agentSkillIndex.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet())
                    .add(skillPackage.getId());
        }

        log.info("[SkillManager] 注册技能包: {}", skillPackage);
        return this;
    }

    /**
     * 注销技能包。
     */
    public SkillPackageManager unregister(String skillId) {
        SkillPackage removed = skillPackages.remove(skillId);
        if (removed != null) {
            // 清理 Agent 索引
            for (String agentId : removed.getBoundAgents().keySet()) {
                Set<String> skills = agentSkillIndex.get(agentId);
                if (skills != null) {
                    skills.remove(skillId);
                    if (skills.isEmpty()) {
                        agentSkillIndex.remove(agentId);
                    }
                }
            }
            log.info("[SkillManager] 注销技能包: {}", removed);
        }
        return this;
    }

    // ==================== 绑定与解绑 ====================

    /**
     * 将技能包绑定到 Agent。
     */
    public SkillPackageManager bind(String skillId, String agentId) {
        SkillPackage pkg = skillPackages.get(skillId);
        if (pkg == null) {
            log.warn("[SkillManager] 技能包不存在: {}", skillId);
            return this;
        }
        pkg.bindToAgent(agentId);
        agentSkillIndex.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet())
                .add(skillId);
        log.info("[SkillManager] 绑定技能包 {} → Agent {}", skillId, agentId);
        return this;
    }

    /**
     * 从 Agent 解绑技能包。
     */
    public SkillPackageManager unbind(String skillId, String agentId) {
        SkillPackage pkg = skillPackages.get(skillId);
        if (pkg != null) {
            pkg.unbindFromAgent(agentId);
        }
        Set<String> skills = agentSkillIndex.get(agentId);
        if (skills != null) {
            skills.remove(skillId);
            if (skills.isEmpty()) {
                agentSkillIndex.remove(agentId);
            }
        }
        log.info("[SkillManager] 解绑技能包 {} → Agent {}", skillId, agentId);
        return this;
    }

    // ==================== 查询 ====================

    /**
     * 获取指定技能包。
     */
    public Optional<SkillPackage> get(String skillId) {
        return Optional.ofNullable(skillPackages.get(skillId));
    }

    /**
     * 获取 Agent 绑定的所有技能包。
     */
    public List<SkillPackage> getAgentSkills(String agentId) {
        Set<String> skillIds = agentSkillIndex.get(agentId);
        if (skillIds == null || skillIds.isEmpty()) return List.of();
        return skillIds.stream()
                .map(skillPackages::get)
                .filter(Objects::nonNull)
                .filter(SkillPackage::isEnabled)
                .toList();
    }

    /**
     * 获取所有已注册技能包。
     */
    public List<SkillPackage> getAll() {
        return List.copyOf(skillPackages.values());
    }

    /**
     * 获取所有已启用的技能包。
     */
    public List<SkillPackage> getEnabled() {
        return skillPackages.values().stream()
                .filter(SkillPackage::isEnabled)
                .toList();
    }

    /**
     * 获取 Agent 的技能注入 prompt。
     *
     * <p>将所有绑定的技能包指令拼接为 prompt 片段，供注入到 system prompt 使用。
     *
     * @param agentId Agent 标识
     * @return 技能注入 prompt 文本，无技能时返回空字符串
     */
    public String buildAgentSkillPrompt(String agentId) {
        List<SkillPackage> skills = getAgentSkills(agentId);
        if (skills.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 技能包指令 ===\n");

        for (SkillPackage skill : skills) {
            String injection = skill.buildInjectionPrompt();
            if (!injection.isBlank()) {
                sb.append(injection).append("\n");
            }
        }

        return sb.toString();
    }
}
