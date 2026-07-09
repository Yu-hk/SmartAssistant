/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import com.example.smartassistant.router.service.guardrail.EmotionLevel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 路由执行结果
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RoutingResult {

    /**
     * Agent 处理结果文本
     */
    private String result;

    /**
     * 目标 Agent 名称
     */
    private String agentName;

    /**
     * 置信度 (0.0 - 1.0)
     */
    private Double confidence;

    /**
     * 意图标签（用于用户画像意图分布统计）
     */
    private String intentTag;

    /**
     * 是否命中了语义缓存（直接从缓存返回，无 Agent 执行）
     */
    @Builder.Default
    private Boolean fromCache = false;

    /**
     * 是否使用了管理员工具（如知识库同步），回复不应纳入缓存
     */
    @Builder.Default
    private Boolean adminOperation = false;

    /**
     * 情绪等级（NONE 表示未检测到情绪风险）
     */
    private EmotionLevel emotionLevel;

    /**
     * 是否触发情绪干预（需共情/疏导/安全兜底）
     */
    private Boolean emotionIntervention;

    /**
     * 是否需禁用工具调用（HEAVY 等级为 true，下游 Agent 调用方应尊重此标志）
     */
    private Boolean disableTools;

    /**
     * 情绪安抚/求助引导话术（可注入回复）
     */
    private String emotionGuidance;

}
