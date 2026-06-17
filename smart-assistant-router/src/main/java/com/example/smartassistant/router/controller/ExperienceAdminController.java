/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.service.experience.AgentKnowledgeService;
import com.example.smartassistant.router.service.experience.ExperienceModel;
import com.example.smartassistant.router.service.experience.ExperienceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 经验管理 REST API
 * <p>
 * 提供经验的列表、查询、删除、统计等管理能力。
 * 对应 AssistantAgent 的 ExperienceManagementController。
 *
 * @author SmartAssistant Team
 */
@RestController
@RequestMapping("/api/experience")
public class ExperienceAdminController {

    private static final Logger log = LoggerFactory.getLogger(ExperienceAdminController.class);

    private final ExperienceService experienceService;
    private final AgentKnowledgeService knowledgeService;

    public ExperienceAdminController(ExperienceService experienceService,
                                     AgentKnowledgeService knowledgeService) {
        this.experienceService = experienceService;
        this.knowledgeService = knowledgeService;
    }

    /**
     * 获取经验统计
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(experienceService.getStatistics());
    }

    /**
     * 按类型列出经验
     */
    @GetMapping("/list")
    public ResponseEntity<List<ExperienceModel>> list(
            @RequestParam(value = "type", required = false) String type) {
        if (type != null && !type.isBlank()) {
            try {
                ExperienceModel.Type expType = ExperienceModel.Type.valueOf(type.toUpperCase());
                return ResponseEntity.ok(experienceService.listByType(expType));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        // 无类型过滤，返回全部
        Set<String> ids = experienceService.listExperienceIds();
        List<ExperienceModel> result = new ArrayList<>();
        for (String id : ids) {
            ExperienceModel exp = experienceService.loadExperience(id);
            if (exp != null) result.add(exp);
        }
        // 按命中次数降序
        result.sort((a, b) -> Integer.compare(b.getHitCount(), a.getHitCount()));
        return ResponseEntity.ok(result);
    }

    /**
     * 查询单个经验
     */
    @GetMapping("/{expId}")
    public ResponseEntity<ExperienceModel> get(@PathVariable String expId) {
        ExperienceModel exp = experienceService.loadExperience(expId);
        if (exp == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(exp);
    }

    /**
     * 删除经验
     */
    @DeleteMapping("/{expId}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String expId) {
        experienceService.deleteExperience(expId);
        return ResponseEntity.ok(Map.of("status", "deleted", "id", expId));
    }

    /**
     * 手动创建经验（供管理后台使用）
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, String>> create(@RequestBody ExperienceModel experience) {
        if (experience.getId() == null || experience.getId().isBlank()) {
            experience.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        experience.setCreatedAt(System.currentTimeMillis());
        experience.setLastHitAt(System.currentTimeMillis());

        experienceService.saveExperience(experience);
        log.info("[ExperienceAdmin] 手动创建经验: id={}, type={}, intent={}",
                experience.getId(), experience.getType(), experience.getIntentTag());

        return ResponseEntity.ok(Map.of("status", "created", "id", experience.getId()));
    }

    /**
     * 清理所有经验
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear() {
        Set<String> ids = experienceService.listExperienceIds();
        int count = 0;
        for (String id : ids) {
            experienceService.deleteExperience(id);
            count++;
        }
        log.info("[ExperienceAdmin] 已清理 {} 条经验", count);
        return ResponseEntity.ok(Map.of("status", "cleared", "count", count));
    }

    // ==================== 知识库导出 ====================

    /**
     * ⭐ 导出指定 Agent 的经验为 MD 知识库。
     * <p>
     * Consumer 在对话结束时调用此端点，将本轮对话学到的经验持久化为 MD。
     * 生成的 MD 文件位于 {@code knowledge/{agentName}_kb.md}，
     * 可被 Agent 启动时注入 system prompt，实现"Agent 认识自己"。
     *
     * <pre>
     * POST /api/experience/export?agentName=order_agent
     * → 写入 knowledge/order_agent_kb.md
     * </pre>
     */
    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> exportKnowledge(
            @RequestParam(defaultValue = "") String agentName) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (agentName.isBlank()) {
            // 导出全部 Agent
            Map<String, String> all = knowledgeService.exportAll();
            result.put("status", "exported");
            result.put("agents", all.keySet());
            result.put("count", all.size());
            log.info("[ExperienceAdmin] 导出全部 {} 个 Agent 知识库", all.size());
        } else {
            String md = knowledgeService.exportToMd(agentName);
            if (md != null) {
                result.put("status", "exported");
                result.put("agentName", agentName);
                result.put("size", md.length());
            } else {
                result.put("status", "empty");
                result.put("agentName", agentName);
            }
            log.info("[ExperienceAdmin] 导出 {} 知识库: {}", agentName, result.get("status"));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * ⭐ 查看 Agent 知识库 MD 内容（供调试和审核）。
     */
    @GetMapping("/export/{agentName}")
    public ResponseEntity<Map<String, Object>> viewKnowledge(
            @PathVariable String agentName) {
        String md = knowledgeService.loadKnowledge(agentName);
        if (md == null) {
            return ResponseEntity.ok(Map.of("status", "empty", "agentName", agentName));
        }
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "agentName", agentName,
                "size", md.length(),
                "content", md));
    }
}
