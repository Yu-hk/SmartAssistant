/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 端到端评测结果 — 记录单次 Agent 执行的全维度评价。
 * <p>
 * 对应面试题 Q29 "如何评估 Agent 的执行效果" 中提出的分层评估：
 * <ul>
 *   <li><b>任务层</b>：任务是否成功完成</li>
 *   <li><b>步骤层</b>：工具选择是否正确、调用时序是否合理</li>
 *   <li><b>系统层</b>：延迟、Token 消耗、迭代次数</li>
 *   <li><b>业务层</b>：人工节省时间、业务指标影响</li>
 * </ul>
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-06
 */
public class AgentEvaluationResult {

    // ==================== 测试标识 ====================

    /** 测试用例 ID */
    private final String caseId;
    /** 测试名称 */
    private final String caseName;
    /** 目标 Agent 名 */
    private final String agentName;
    /** 用户输入 */
    private final String input;
    /** 预期意图标签 */
    private final String expectedIntent;
    /** 预期工具调用列表 */
    private final List<String> expectedTools;
    /** 预期回复包含的关键词 */
    private final List<String> expectedKeywords;

    // ==================== 实际结果 ====================

    /** 实际 Agent 回复 */
    private String actualResponse;
    /** 实际意图标签 */
    private String actualIntent;
    /** 实际工具调用轨迹 */
    private List<String> actualToolsCalled;
    /** 实际工具调用次数 */
    private int actualToolCallCount;
    /** 实际迭代次数（ReAct 循环） */
    private int actualIterations;
    /** 总延迟（ms） */
    private long actualLatencyMs;
    /** Token 消耗（input + output） */
    private int totalTokens;
    /** 是否发生错误 */
    private boolean hasError;
    /** 错误信息 */
    private String errorMessage;

    // ==================== 评测分数 ====================

    /** 意图匹配（0.0 ~ 1.0） */
    private double intentMatchScore;
    /** 工具选择准确率（0.0 ~ 1.0） */
    private double toolSelectionAccuracy;
    /** 回复质量评分（0.0 ~ 1.0） */
    private double responseQualityScore;
    /** 效率评分（基于迭代次数和延迟，0.0 ~ 1.0） */
    private double efficiencyScore;
    /** 综合分（0.0 ~ 1.0） */
    private double compositeScore;

    // ==================== 详细日志 ====================

    /** 执行时间戳 */
    private final long timestamp;
    /** 备注 */
    private String notes;

    // ==================== 构造 ====================

    private AgentEvaluationResult(Builder builder) {
        this.caseId = builder.caseId;
        this.caseName = builder.caseName;
        this.agentName = builder.agentName;
        this.input = builder.input;
        this.expectedIntent = builder.expectedIntent;
        this.expectedTools = builder.expectedTools;
        this.expectedKeywords = builder.expectedKeywords;
        this.actualResponse = builder.actualResponse;
        this.actualIntent = builder.actualIntent;
        this.actualToolsCalled = builder.actualToolsCalled;
        this.actualToolCallCount = builder.actualToolCallCount;
        this.actualIterations = builder.actualIterations;
        this.actualLatencyMs = builder.actualLatencyMs;
        this.totalTokens = builder.totalTokens;
        this.hasError = builder.hasError;
        this.errorMessage = builder.errorMessage;
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
        this.notes = builder.notes;

        // 自动计算评分
        computeScores();
    }

    // ==================== 评分计算 ====================

    private void computeScores() {
        // 1. 意图匹配
        if (expectedIntent != null && actualIntent != null) {
            intentMatchScore = expectedIntent.equals(actualIntent) ? 1.0 : 0.0;
        } else {
            intentMatchScore = 1.0; // 未指定预期意图时默认通过
        }

        // 2. 工具选择准确率
        if (expectedTools != null && !expectedTools.isEmpty()) {
            if (actualToolsCalled != null && !actualToolsCalled.isEmpty()) {
                Set<String> expectedSet = new HashSet<>(expectedTools);
                Set<String> actualSet = new HashSet<>(actualToolsCalled);
                long correct = expectedSet.stream().filter(actualSet::contains).count();
                long total = Math.max(expectedSet.size(), actualSet.size());
                toolSelectionAccuracy = total > 0 ? (double) correct / total : 0.0;
            } else {
                toolSelectionAccuracy = 0.0;
            }
        } else {
            toolSelectionAccuracy = 1.0; // 未指定预期工具时默认通过
        }

        // 3. 回复质量（基于关键词是否命中）
        if (expectedKeywords != null && !expectedKeywords.isEmpty() && actualResponse != null) {
            String resp = actualResponse.toLowerCase();
            long keywordHits = expectedKeywords.stream()
                    .filter(kw -> resp.contains(kw.toLowerCase()))
                    .count();
            responseQualityScore = (double) keywordHits / expectedKeywords.size();
        } else {
            responseQualityScore = hasError ? 0.0 : 1.0;
        }

        // 4. 效率评分
        double iterScore = Math.max(0, 1.0 - (actualIterations * 0.1)); // 每多一次迭代扣0.1
        double latencyScore = Math.max(0, 1.0 - (actualLatencyMs / 30000.0)); // 30s以上扣完
        efficiencyScore = 0.5 * iterScore + 0.5 * latencyScore;

        // 5. 综合分
        compositeScore = 0.35 * intentMatchScore
                + 0.30 * responseQualityScore
                + 0.20 * toolSelectionAccuracy
                + 0.15 * efficiencyScore;

        // 有错误时综合分归零
        if (hasError) {
            compositeScore = Math.min(compositeScore, 0.3);
        }
    }

    // ==================== 判定 ====================

    /** 是否通过评测（综合分 >= 0.6） */
    public boolean passed() {
        return !hasError && compositeScore >= 0.6;
    }

    // ==================== Getters ====================

    public String getCaseId() { return caseId; }
    public String getCaseName() { return caseName; }
    public String getAgentName() { return agentName; }
    public String getInput() { return input; }
    public String getExpectedIntent() { return expectedIntent; }
    public List<String> getExpectedTools() { return expectedTools; }
    public List<String> getExpectedKeywords() { return expectedKeywords; }
    public String getActualResponse() { return actualResponse; }
    public String getActualIntent() { return actualIntent; }
    public List<String> getActualToolsCalled() { return actualToolsCalled; }
    public int getActualToolCallCount() { return actualToolCallCount; }
    public int getActualIterations() { return actualIterations; }
    public long getActualLatencyMs() { return actualLatencyMs; }
    public int getTotalTokens() { return totalTokens; }
    public boolean isHasError() { return hasError; }
    public String getErrorMessage() { return errorMessage; }
    public double getIntentMatchScore() { return intentMatchScore; }
    public double getToolSelectionAccuracy() { return toolSelectionAccuracy; }
    public double getResponseQualityScore() { return responseQualityScore; }
    public double getEfficiencyScore() { return efficiencyScore; }
    public double getCompositeScore() { return compositeScore; }
    public long getTimestamp() { return timestamp; }
    public String getNotes() { return notes; }

    @Override
    public String toString() {
        return String.format("[%s] %s | Agent=%s | 综合=%.2f | 意图=%.2f | 工具=%.2f | 质量=%.2f | 效率=%.2f | 迭代=%d | 延迟=%dms | %s",
                passed() ? "✅" : "❌",
                caseId != null ? caseId : "?",
                agentName,
                compositeScore,
                intentMatchScore,
                toolSelectionAccuracy,
                responseQualityScore,
                efficiencyScore,
                actualIterations,
                actualLatencyMs,
                hasError ? ("ERROR: " + errorMessage) : "");
    }

    // ==================== Builder ====================

    public static class Builder {
        private final String caseId;
        private String caseName = "";
        private String agentName = "";
        private String input = "";
        private String expectedIntent;
        private List<String> expectedTools;
        private List<String> expectedKeywords;
        private String actualResponse;
        private String actualIntent;
        private List<String> actualToolsCalled = new ArrayList<>();
        private int actualToolCallCount;
        private int actualIterations;
        private long actualLatencyMs;
        private int totalTokens;
        private boolean hasError;
        private String errorMessage;
        private long timestamp;
        private String notes;

        public Builder(String caseId) {
            this.caseId = caseId;
        }

        public Builder caseName(String val) { this.caseName = val; return this; }
        public Builder agentName(String val) { this.agentName = val; return this; }
        public Builder input(String val) { this.input = val; return this; }
        public Builder expectedIntent(String val) { this.expectedIntent = val; return this; }
        public Builder expectedTools(List<String> val) { this.expectedTools = val; return this; }
        public Builder expectedKeywords(List<String> val) { this.expectedKeywords = val; return this; }
        public Builder actualResponse(String val) { this.actualResponse = val; return this; }
        public Builder actualIntent(String val) { this.actualIntent = val; return this; }
        public Builder actualToolsCalled(List<String> val) {
            this.actualToolsCalled = val != null ? val : new ArrayList<>();
            return this;
        }
        public Builder actualToolCallCount(int val) { this.actualToolCallCount = val; return this; }
        public Builder actualIterations(int val) { this.actualIterations = val; return this; }
        public Builder actualLatencyMs(long val) { this.actualLatencyMs = val; return this; }
        public Builder totalTokens(int val) { this.totalTokens = val; return this; }
        public Builder hasError(boolean val) { this.hasError = val; return this; }
        public Builder errorMessage(String val) { this.errorMessage = val; return this; }
        public Builder timestamp(long val) { this.timestamp = val; return this; }
        public Builder notes(String val) { this.notes = val; return this; }

        public AgentEvaluationResult build() {
            return new AgentEvaluationResult(this);
        }
    }

    // ==================== 批量报告 ====================

    /**
     * 从多个评测结果生成汇总报告。
     *
     * @param results Agent 评测结果列表
     * @return 格式化报告
     */
    public static String generateBatchReport(List<AgentEvaluationResult> results) {
        if (results == null || results.isEmpty()) {
            return "无 Agent 评测数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════\n");
        sb.append("       Agent 批量评测报告\n");
        sb.append("═══════════════════════════════════════\n\n");

        int total = results.size();
        long passed = results.stream().filter(AgentEvaluationResult::passed).count();
        sb.append(String.format("总计: %d | 通过: %d | 失败: %d | 通过率: %.1f%%\n\n",
                total, passed, total - passed, total > 0 ? passed * 100.0 / total : 0));

        // 按 Agent 分组统计
        Map<String, List<AgentEvaluationResult>> byAgent = results.stream()
                .collect(Collectors.groupingBy(AgentEvaluationResult::getAgentName));
        sb.append("按 Agent 统计:\n");
        for (Map.Entry<String, List<AgentEvaluationResult>> entry : byAgent.entrySet()) {
            long agentPassed = entry.getValue().stream().filter(AgentEvaluationResult::passed).count();
            int agentTotal = entry.getValue().size();
            double avgScore = entry.getValue().stream()
                    .mapToDouble(AgentEvaluationResult::getCompositeScore).average().orElse(0);
            sb.append(String.format("  %s: %d/%d (%d%%) | 平均分=%.2f\n",
                    entry.getKey(), agentPassed, agentTotal,
                    agentTotal > 0 ? agentPassed * 100 / agentTotal : 0, avgScore));
        }
        sb.append("\n");

        // 平均指标
        double avgScore = results.stream().mapToDouble(AgentEvaluationResult::getCompositeScore).average().orElse(0);
        double avgToolAccuracy = results.stream().mapToDouble(AgentEvaluationResult::getToolSelectionAccuracy).average().orElse(0);
        double avgQuality = results.stream().mapToDouble(AgentEvaluationResult::getResponseQualityScore).average().orElse(0);
        double avgIntentMatch = results.stream().mapToDouble(AgentEvaluationResult::getIntentMatchScore).average().orElse(0);
        double avgLatency = results.stream().mapToLong(AgentEvaluationResult::getActualLatencyMs).average().orElse(0);
        double avgIterations = results.stream().mapToInt(AgentEvaluationResult::getActualIterations).average().orElse(0);

        sb.append("--- 全维度平均分 ---\n");
        sb.append(String.format("  意图匹配率: %.4f\n", avgIntentMatch));
        sb.append(String.format("  工具选择准确率: %.4f\n", avgToolAccuracy));
        sb.append(String.format("  回复质量: %.4f\n", avgQuality));
        sb.append(String.format("  平均延迟: %.0fms\n", avgLatency));
        sb.append(String.format("  平均迭代: %.1f 轮\n", avgIterations));
        sb.append(String.format("  综合评分: %.4f\n", avgScore));
        sb.append("\n");

        // 失败详情
        sb.append("--- 失败详情 ---\n");
        boolean hasFailures = false;
        for (AgentEvaluationResult r : results) {
            if (!r.passed()) {
                hasFailures = true;
                sb.append("  ").append(r.toString()).append("\n");
            }
        }
        if (!hasFailures) {
            sb.append("  全部通过 ✅\n");
        }

        sb.append("\n═══════════════════════════════════════\n");
        return sb.toString();
    }
}
