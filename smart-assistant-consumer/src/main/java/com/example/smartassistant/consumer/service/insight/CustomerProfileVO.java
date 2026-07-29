/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.insight;

import java.util.List;
import java.util.Map;

/**
 * 客户 360° 聚合视图 — 坐席面板「客户画像」卡片的数据源。
 *
 * <p>聚合 UserProfile（JSON 文件偏好/意图分布）+ EntityProfile（Redis KV 事实）
 * + AgentMemory（坐席笔记摘要）为统一 VO，前端 InsightPanel 直接消费。</p>
 *
 * @param userName              客户姓名（来自当前会话）
 * @param totalQueries          历史查询总数
 * @param intentDistribution    意图分布（refund/order/tech/general × 计数）
 * @param entityFacts           Redis 实体画像（preference/fear/hobby/location/name）
 * @param foodPreferences       美食偏好列表
 * @param travelPreferences     旅行偏好列表
 * @param budgetRange           预算范围
 * @param dietaryRestrictions   饮食限制
 * @param preferenceWeights     偏好权重（"川菜": 10）
 * @param escalationCount       升级/投诉计数
 * @param complaintCount        投诉触发次数
 * @param lastEmotionLabel      最近一次情绪标签（⭐ P2-A 持久化回流）
 * @param lastEmotionScore      最近一次情绪分数 (0-100)
 * @param negativeTouchCount    负面情绪触碰次数
 * @param positiveTouchCount    正面情绪触碰次数
 * @param emotionAvgScore       情绪分数滑动平均 (0-100)
 * @param agentMemorySummaries  各 Agent 坐席笔记摘要
 * @param emotionHistory        情绪历史趋势（近 N 条）
 */
public record CustomerProfileVO(
        String userName,
        int totalQueries,
        Map<String, Integer> intentDistribution,
        Map<String, String> entityFacts,
        List<String> foodPreferences,
        List<String> travelPreferences,
        String budgetRange,
        List<String> dietaryRestrictions,
        Map<String, Integer> preferenceWeights,
        int escalationCount,
        int complaintCount,
        String lastEmotionLabel,
        int lastEmotionScore,
        int negativeTouchCount,
        int positiveTouchCount,
        Double emotionAvgScore,
        List<String> agentMemorySummaries,
        List<EmotionSnapshot> emotionHistory) {

    /** 情绪快照 — 单条情绪记录（时间戳 + 等级 + 触发话题） */
    public record EmotionSnapshot(
            String timestamp,
            int score,
            String label,
            String triggerTopic) {}
}
