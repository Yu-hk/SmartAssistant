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
 * 覆盖 12 层 Agent 可标注评测维度：意图识别、多意图拆分、隐含意图、拒识、
 * 澄清判断、输入鲁棒性、实体识别、实体归一、词槽填充、词槽缺失、词槽冲突、词槽追问。
 * </p>
 *
 * @see com.example.smartassistant.router.service.taskanalysis.TaskAnalysisService
 */
public class TaskAnalysisResult {

    // ======================== 已有字段 ========================

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

    // ======================== 新增字段 ========================

    // ---------- 1. 多意图拆分 ----------

    /** 子意图列表：一句话可能包含多个任务 */
    private List<Map<String, Object>> subIntents;

    // ---------- 2. 隐含意图 ----------

    /** 隐含意图列表：用户没直说但能推断的目标 */
    private List<Map<String, Object>> implicitIntents;

    // ---------- 3. 实体归一化 ----------

    /** 归一化实体（{entity_type: standard_value}） */
    private Map<String, String> normalizedEntities;

    /** 原始→标准映射明细 */
    private List<Map<String, Object>> normalizationDetails;

    // ---------- 4. 词槽状态 ----------

    /** 已填词槽 */
    private List<String> filledSlots;

    /** 缺失词槽 */
    private List<String> missingSlots;

    /** 可默认词槽 */
    private List<String> defaultableSlots;

    /** 词槽冲突列表 */
    private List<Map<String, Object>> slotConflicts;

    // ---------- 5. 澄清判断 ----------

    /** 是否需要澄清 */
    private boolean needsClarification;

    /** 澄清原因 */
    private String clarificationReason;

    /** 建议追问的问题 */
    private List<String> clarificationQuestions;

    /** 追问优先级排序的槽位 */
    private List<String> clarificationSlotPriority;

    // ---------- 6. 输入鲁棒性 ----------

    /** 标准化后的输入文本 */
    private String standardizedInput;

    /** 纠错记录（[{original: "杭洲", corrected: "杭州", type: "typo"}]） */
    private List<Map<String, Object>> inputCorrections;

    /** 输入噪声类型：typo/abbreviation/omission/speech/other */
    private List<String> noiseTypes;

    // ======================== 构造方法 ========================

    public TaskAnalysisResult() {
        this.entities = new HashMap<>();
        this.actionConstraints = new ArrayList<>();
        this.outputConstraints = new ArrayList<>();
        this.riskFlags = new ArrayList<>();
        this.toolScores = new HashMap<>();
        this.subIntents = new ArrayList<>();
        this.implicitIntents = new ArrayList<>();
        this.normalizedEntities = new HashMap<>();
        this.normalizationDetails = new ArrayList<>();
        this.filledSlots = new ArrayList<>();
        this.missingSlots = new ArrayList<>();
        this.defaultableSlots = new ArrayList<>();
        this.slotConflicts = new ArrayList<>();
        this.clarificationQuestions = new ArrayList<>();
        this.clarificationSlotPriority = new ArrayList<>();
        this.inputCorrections = new ArrayList<>();
        this.noiseTypes = new ArrayList<>();
    }

    public static TaskAnalysisResult empty() {
        return new TaskAnalysisResult();
    }

    // ======================== Getters & Setters ========================

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

    // --- 新增字段 getters/setters ---

    public List<Map<String, Object>> getSubIntents() { return subIntents; }
    public void setSubIntents(List<Map<String, Object>> subIntents) { this.subIntents = subIntents != null ? subIntents : new ArrayList<>(); }

    public List<Map<String, Object>> getImplicitIntents() { return implicitIntents; }
    public void setImplicitIntents(List<Map<String, Object>> implicitIntents) { this.implicitIntents = implicitIntents != null ? implicitIntents : new ArrayList<>(); }

    public Map<String, String> getNormalizedEntities() { return normalizedEntities; }
    public void setNormalizedEntities(Map<String, String> normalizedEntities) { this.normalizedEntities = normalizedEntities != null ? normalizedEntities : new HashMap<>(); }

    public List<Map<String, Object>> getNormalizationDetails() { return normalizationDetails; }
    public void setNormalizationDetails(List<Map<String, Object>> normalizationDetails) { this.normalizationDetails = normalizationDetails != null ? normalizationDetails : new ArrayList<>(); }

    public List<String> getFilledSlots() { return filledSlots; }
    public void setFilledSlots(List<String> filledSlots) { this.filledSlots = filledSlots != null ? filledSlots : new ArrayList<>(); }

    public List<String> getMissingSlots() { return missingSlots; }
    public void setMissingSlots(List<String> missingSlots) { this.missingSlots = missingSlots != null ? missingSlots : new ArrayList<>(); }

    public List<String> getDefaultableSlots() { return defaultableSlots; }
    public void setDefaultableSlots(List<String> defaultableSlots) { this.defaultableSlots = defaultableSlots != null ? defaultableSlots : new ArrayList<>(); }

    public List<Map<String, Object>> getSlotConflicts() { return slotConflicts; }
    public void setSlotConflicts(List<Map<String, Object>> slotConflicts) { this.slotConflicts = slotConflicts != null ? slotConflicts : new ArrayList<>(); }

    public boolean isNeedsClarification() { return needsClarification; }
    public void setNeedsClarification(boolean needsClarification) { this.needsClarification = needsClarification; }

    public String getClarificationReason() { return clarificationReason; }
    public void setClarificationReason(String clarificationReason) { this.clarificationReason = clarificationReason; }

    public List<String> getClarificationQuestions() { return clarificationQuestions; }
    public void setClarificationQuestions(List<String> clarificationQuestions) { this.clarificationQuestions = clarificationQuestions != null ? clarificationQuestions : new ArrayList<>(); }

    public List<String> getClarificationSlotPriority() { return clarificationSlotPriority; }
    public void setClarificationSlotPriority(List<String> clarificationSlotPriority) { this.clarificationSlotPriority = clarificationSlotPriority != null ? clarificationSlotPriority : new ArrayList<>(); }

    public String getStandardizedInput() { return standardizedInput; }
    public void setStandardizedInput(String standardizedInput) { this.standardizedInput = standardizedInput; }

    public List<Map<String, Object>> getInputCorrections() { return inputCorrections; }
    public void setInputCorrections(List<Map<String, Object>> inputCorrections) { this.inputCorrections = inputCorrections != null ? inputCorrections : new ArrayList<>(); }

    public List<String> getNoiseTypes() { return noiseTypes; }
    public void setNoiseTypes(List<String> noiseTypes) { this.noiseTypes = noiseTypes != null ? noiseTypes : new ArrayList<>(); }

    // ======================== 便利方法 ========================

    public boolean hasEntities() { return entities != null && !entities.isEmpty(); }
    public boolean hasConstraints() {
        return (actionConstraints != null && !actionConstraints.isEmpty())
                || (outputConstraints != null && !outputConstraints.isEmpty());
    }
    public boolean hasRisks() { return riskFlags != null && !riskFlags.isEmpty(); }
    public boolean hasToolScores() { return toolScores != null && !toolScores.isEmpty(); }

    /** 是否有子意图（多意图拆分） */
    public boolean hasSubIntents() { return subIntents != null && subIntents.size() > 1; }

    /** 是否有隐含意图 */
    public boolean hasImplicitIntents() { return implicitIntents != null && !implicitIntents.isEmpty(); }

    /** 是否有词槽缺失 */
    public boolean hasMissingSlots() { return missingSlots != null && !missingSlots.isEmpty(); }

    /** 是否有词槽冲突 */
    public boolean hasSlotConflicts() { return slotConflicts != null && !slotConflicts.isEmpty(); }

    /** 是否有归一化实体 */
    public boolean hasNormalizedEntities() { return normalizedEntities != null && !normalizedEntities.isEmpty(); }

    /** 是否有纠错 */
    public boolean hasInputCorrections() { return inputCorrections != null && !inputCorrections.isEmpty(); }

    /** 是否包含实质性内容 */
    public boolean isMeaningful() {
        return intentCategory != null || hasEntities() || hasConstraints()
                || hasRisks() || taskGoal != null || hasToolScores()
                || hasSubIntents() || hasImplicitIntents()
                || hasMissingSlots() || hasSlotConflicts()
                || hasNormalizedEntities() || hasInputCorrections()
                || needsClarification || standardizedInput != null;
    }

    @Override
    public String toString() {
        return "TaskAnalysisResult{"
                + "intentCategory='" + intentCategory + '\''
                + ", entities=" + entities
                + ", subIntents=" + subIntents
                + ", implicitIntents=" + implicitIntents
                + ", normalizedEntities=" + normalizedEntities
                + ", missingSlots=" + missingSlots
                + ", slotConflicts=" + slotConflicts
                + ", needsClarification=" + needsClarification
                + ", standardizedInput='" + standardizedInput + '\''
                + ", taskGoal='" + taskGoal + '\''
                + ", riskFlags=" + riskFlags
                + '}';
    }
}
