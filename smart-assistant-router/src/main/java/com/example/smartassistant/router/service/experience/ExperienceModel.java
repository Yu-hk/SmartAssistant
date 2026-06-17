/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.experience;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Arrays;
import java.util.List;

/**
 * 统一经验模型——从 AssistantAgent 的经验体系借鉴（COMMON / REACT / TOOL 三类）。
 * <p>
 * 经验是对一次成功的 Agent 执行过程的抽象，包括：
 * <ul>
 *   <li><b>COMMON</b> — 通用知识经验：描述某个意图下应该路由到哪个 Agent</li>
 *   <li><b>REACT</b> — 推理步骤经验：描述多步推理的链路（子任务分解模式）</li>
 *   <li><b>TOOL</b> — 工具调用经验：描述完成某个意图的最佳工具调用模式（含参数）</li>
 * </ul>
 * <p>
 * 经验通过 {@link ExperienceService} 管理，在 Agent 执行后自动提取，
 * 并在下次相似请求到达时优先匹配，跳过 LLM 推理。
 *
 * @author SmartAssistant Team
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "experienceType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ExperienceModel.CommonExperience.class, name = "COMMON"),
    @JsonSubTypes.Type(value = ExperienceModel.ReactExperience.class, name = "REACT"),
    @JsonSubTypes.Type(value = ExperienceModel.ToolExperience.class, name = "TOOL")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class ExperienceModel {

    /** 经验唯一标识 */
    private String id;

    /** 经验类型 */
    private Type type;

    /** 意图标签（由关键词生成，如 "订单,状态,查询"） */
    private String intentTag;

    /** 触发关键词集合（用于匹配） */
    private List<String> triggerKeywords;

    /** 目标 Agent 名称 */
    private String agentName;

    /** 使用次数 */
    private int hitCount;

    /** 最后使用时间戳 */
    private long lastHitAt;

    /** 创建时间戳 */
    private long createdAt;

    /** 置信度分数 (0.0 ~ 1.0) */
    private double confidence;

    /** ⭐ 商品类型分类（Product Agent 专用），如 iPhone 15 Pro / MacBook / 耳机 */
    private String productType;

    /** BGE 向量嵌入（384维，不持久化到 JSON，由 ExperienceService 单独管理） */
    private transient float[] embedding;

    /**
     * 计算当前经验的 BGE embedding 与查询向量的余弦相似度。
     *
     * @param queryVec 查询文本的 BGE 归一化向量
     * @return 余弦相似度 [-1, 1]，归一化向量等效于点积
     */
    public double cosineSimilarity(float[] queryVec) {
        if (embedding == null || queryVec == null) return 0;
        if (embedding.length != queryVec.length) return 0;
        double dot = 0;
        for (int i = 0; i < embedding.length; i++) {
            dot += (double) embedding[i] * queryVec[i];
        }
        return dot; // 归一化向量，点积即余弦
    }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    /** 经验类型枚举 */
    public enum Type {
        /** 通用知识经验：意图 → Agent 路由映射 */
        COMMON,
        /** 推理步骤经验：多步推理链路模式 */
        REACT,
        /** 工具调用经验：特定工具调用模式（含推荐参数） */
        TOOL
    }

    // ==================== COMMON 经验 ====================

    /**
     * 通用知识经验 —— 描述"什么意图应该路由到什么 Agent"。
     * <p>
     * 这是最基础的经验类型，由 {@link ExperienceService} 在每次路由决策后自动提取。
     * 下次相同意图的请求可直接命中此经验，跳过 TaskPlanner 的 LLM 分类。
     */
    public static class CommonExperience extends ExperienceModel {
        /** 路由置信度 (0.0 ~ 1.0) */
        private double routingConfidence;

        /** 兜底 Agent（如果首选 Agent 不可用时的备选） */
        private String fallbackAgent;

        public CommonExperience() {
            setType(Type.COMMON);
        }

        public CommonExperience(String id, String intentTag, List<String> triggerKeywords,
                                String agentName, String fallbackAgent, double routingConfidence) {
            this();
            this.setId(id);
            this.setIntentTag(intentTag);
            this.setTriggerKeywords(triggerKeywords);
            this.setAgentName(agentName);
            this.fallbackAgent = fallbackAgent;
            this.routingConfidence = routingConfidence;
            this.setCreatedAt(System.currentTimeMillis());
            this.setConfidence(0.7);
        }

        public double getRoutingConfidence() { return routingConfidence; }
        public void setRoutingConfidence(double routingConfidence) { this.routingConfidence = routingConfidence; }

        public String getFallbackAgent() { return fallbackAgent; }
        public void setFallbackAgent(String fallbackAgent) { this.fallbackAgent = fallbackAgent; }
    }

    // ==================== REACT 经验 ====================

    /**
     * 推理步骤经验 —— 描述完成某个意图的多步推理链路。
     * <p>
     * 例如 "退单" 意图的推理链：
     * <pre>
     *   step 1: 查订单状态 → order_agent
     *   step 2: step1.status=="paid" → 发起退款 → order_agent
     *   step 3: 通知用户 → general_agent
     * </pre>
     * 下次相同意图直接按此链路执行，无需 LLM 重新推理。
     */
    public static class ReactExperience extends ExperienceModel {
        /** 推理步骤列表 */
        private List<ReactStep> steps;

        public ReactExperience() {
            setType(Type.REACT);
        }

        public ReactExperience(String id, String intentTag, List<String> triggerKeywords,
                               String agentName, List<ReactStep> steps) {
            this();
            this.setId(id);
            this.setIntentTag(intentTag);
            this.setTriggerKeywords(triggerKeywords);
            this.setAgentName(agentName);
            this.steps = steps;
            this.setCreatedAt(System.currentTimeMillis());
            this.setConfidence(0.6);
        }

        public List<ReactStep> getSteps() { return steps; }
        public void setSteps(List<ReactStep> steps) { this.steps = steps; }

        /** 推理步骤 */
        public static class ReactStep {
            /** 步骤序号 */
            private int order;
            /** 步骤描述 */
            private String description;
            /** 目标 Agent */
            private String targetAgent;
            /** 条件（可选）：前一步结果满足条件时才执行此步 */
            private String condition;

            public ReactStep() {}

            public ReactStep(int order, String description, String targetAgent) {
                this.order = order;
                this.description = description;
                this.targetAgent = targetAgent;
            }

            public int getOrder() { return order; }
            public void setOrder(int order) { this.order = order; }

            public String getDescription() { return description; }
            public void setDescription(String description) { this.description = description; }

            public String getTargetAgent() { return targetAgent; }
            public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }

            public String getCondition() { return condition; }
            public void setCondition(String condition) { this.condition = condition; }
        }
    }

    // ==================== TOOL 经验 ====================

    /**
     * 工具调用经验 —— 描述完成某个意图的最佳工具调用模式。
     * <p>
     * 例如 "查订单ORD-001" 意图：
     * <pre>
     *   tool: queryOrder
     *   params: {"orderId": "ORD-001"}
     *   resultPattern: "订单{orderId}当前状态为{status}"
     * </pre>
     * 下次相同意图直接调用工具并格式化结果，跳过 LLM 调用。
     */
    public static class ToolExperience extends ExperienceModel {
        /** 工具名称 */
        private String toolName;
        /** 推荐参数（JSON 模板） */
        private String recommendedParams;
        /** 结果格式化模板 */
        private String resultTemplate;
        /** 成功匹配次数 */
        private int successCount;

        public ToolExperience() {
            setType(Type.TOOL);
        }

        public ToolExperience(String id, String intentTag, List<String> triggerKeywords,
                              String agentName, String toolName, String recommendedParams, String resultTemplate) {
            this();
            this.setId(id);
            this.setIntentTag(intentTag);
            this.setTriggerKeywords(triggerKeywords);
            this.setAgentName(agentName);
            this.toolName = toolName;
            this.recommendedParams = recommendedParams;
            this.resultTemplate = resultTemplate;
            this.setCreatedAt(System.currentTimeMillis());
            this.setConfidence(0.8);
        }

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }

        public String getRecommendedParams() { return recommendedParams; }
        public void setRecommendedParams(String recommendedParams) { this.recommendedParams = recommendedParams; }

        public String getResultTemplate() { return resultTemplate; }
        public void setResultTemplate(String resultTemplate) { this.resultTemplate = resultTemplate; }

        public int getSuccessCount() { return successCount; }
        public void setSuccessCount(int successCount) { this.successCount = successCount; }
    }

    // ==================== Getters / Setters ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getIntentTag() { return intentTag; }
    public void setIntentTag(String intentTag) { this.intentTag = intentTag; }

    public List<String> getTriggerKeywords() { return triggerKeywords; }
    public void setTriggerKeywords(List<String> triggerKeywords) { this.triggerKeywords = triggerKeywords; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public int getHitCount() { return hitCount; }
    public void setHitCount(int hitCount) { this.hitCount = hitCount; }

    public long getLastHitAt() { return lastHitAt; }
    public void setLastHitAt(long lastHitAt) { this.lastHitAt = lastHitAt; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }
}
