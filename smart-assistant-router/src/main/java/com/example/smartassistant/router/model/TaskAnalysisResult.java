/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import java.util.*;

/**
 * 任务分析结果——对用户问题的结构化分析，由 {@code TaskAnalysisService} 产出。
 * <p>
 * 参考 ThinkingAgent 的任务分析设计：将模糊自然语言请求转化为结构化信息，
 * 包含实体提取、约束识别、风险标记和工具相关性评分。
 * 下游 Agent 调用时可据此精准执行，Router 可据此优化路由决策。
 * </p>
 *
 * @see com.example.smartassistant.router.service.taskanalysis.TaskAnalysisService
 */
public class TaskAnalysisResult {

    /** 规范化意图分类：ORDER / PRODUCT / GENERAL / COMPLEX / UNKNOWN */
    private String intentCategory;

    /** 提取的关键实体（orderId, productName, date, amount, location, currency 等） */
    private Map<String, Object> entities;

    /** 行为约束（"只查不写"、"不要退单" 等） */
    private List<String> actionConstraints;

    /** 输出约束（"Markdown格式"、"500字以内" 等） */
    private List<String> outputConstraints;

    /** 风险标记（"涉及退款"、"需二次确认"、"数据敏感" 等） */
    private List<String> riskFlags;

    /** 任务目标——一句话概括用户想做什么 */
    private String taskGoal;

    /** 工具相关性评分（{工具名: 0.0~1.0}，仅对当前问题相关工具打分） */
    private Map<String, Double> toolScores;

    public TaskAnalysisResult() {
        this.entities = new HashMap<>();
        this.actionConstraints = new ArrayList<>();
        this.outputConstraints = new ArrayList<>();
        this.riskFlags = new ArrayList<>();
        this.toolScores = new HashMap<>();
    }

    public static TaskAnalysisResult empty() {
        return new TaskAnalysisResult();
    }

    // ------- Getters & Setters -------

    public String getIntentCategory() { return intentCategory; }
    public void setIntentCategory(String intentCategory) { this.intentCategory = intentCategory; }

    public Map<String, Object> getEntities() { return entities; }
    public void setEntities(Map<String, Object> entities) { this.entities = entities != null ? entities : new HashMap<>(); }

    public List<String> getActionConstraints() { return actionConstraints; }
    public void setActionConstraints(List<String> actionConstraints) { this.actionConstraints = actionConstraints != null ? actionConstraints : new ArrayList<>(); }

    public List<String> getOutputConstraints() { return outputConstraints; }
    public void setOutputConstraints(List<String> outputConstraints) { this.outputConstraints = outputConstraints != null ? outputConstraints : new ArrayList<>(); }

    public List<String> getRiskFlags() { return riskFlags; }
    public void setRiskFlags(List<String> riskFlags) { this.riskFlags = riskFlags != null ? riskFlags : new ArrayList<>(); }

    public String getTaskGoal() { return taskGoal; }
    public void setTaskGoal(String taskGoal) { this.taskGoal = taskGoal; }

    public Map<String, Double> getToolScores() { return toolScores; }
    public void setToolScores(Map<String, Double> toolScores) { this.toolScores = toolScores != null ? toolScores : new HashMap<>(); }

    // ------- 便利方法 -------

    /** 是否有实体信息 */
    public boolean hasEntities() {
        return entities != null && !entities.isEmpty();
    }

    /** 是否有约束信息 */
    public boolean hasConstraints() {
        return (actionConstraints != null && !actionConstraints.isEmpty())
                || (outputConstraints != null && !outputConstraints.isEmpty());
    }

    /** 是否有风险标记 */
    public boolean hasRisks() {
        return riskFlags != null && !riskFlags.isEmpty();
    }

    /** 是否有工具评分 */
    public boolean hasToolScores() {
        return toolScores != null && !toolScores.isEmpty();
    }

    /** 是否包含实质性内容（非空结果） */
    public boolean isMeaningful() {
        return intentCategory != null || hasEntities() || hasConstraints()
                || hasRisks() || taskGoal != null || hasToolScores();
    }

    @Override
    public String toString() {
        return "TaskAnalysisResult{"
                + "intentCategory='" + intentCategory + '\''
                + ", entities=" + entities
                + ", actionConstraints=" + actionConstraints
                + ", outputConstraints=" + outputConstraints
                + ", riskFlags=" + riskFlags
                + ", taskGoal='" + taskGoal + '\''
                + ", toolScores=" + toolScores
                + '}';
    }
}
