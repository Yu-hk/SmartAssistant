/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 澄清判断服务——决定何时追问、追问什么、如何排序。
 * <p>
 * 对应评测维度：澄清判断、词槽追问。
 * </p>
 * <p>
 * 核心原则：
 * <ul>
 *   <li>只问当前阶段**必须问**的问题</li>
 *   <li>用户回答后能推进下一步</li>
 *   <li>不提前索要支付、乘客等非当前阶段必要信息</li>
 *   <li>区分三类缺口：可默认/需追问/必须确认</li>
 * </ul>
 * </p>
 */
@Service
public class ClarificationService {

    private static final Logger log = LoggerFactory.getLogger(ClarificationService.class);

    /** 槽位→建议追问问题映射 */
    private static final Map<String, String> QUESTION_TEMPLATES = new LinkedHashMap<>();
    static {
        QUESTION_TEMPLATES.put("order_id", "请问您的订单号是多少？");
        QUESTION_TEMPLATES.put("departure_station", "请问从哪个站出发？");
        QUESTION_TEMPLATES.put("arrival_station", "请问到哪个站？");
        QUESTION_TEMPLATES.put("departure_date", "请问您计划哪天出发？");
        QUESTION_TEMPLATES.put("departure_time", "请问您希望什么时间段出发？");
        QUESTION_TEMPLATES.put("passenger", "请问乘车人是谁？（请提供姓名）");
        QUESTION_TEMPLATES.put("seat_type", "请问您想选什么座位类型？（二等座/一等座/商务座）");
        QUESTION_TEMPLATES.put("train_type", "请问您想坐高铁还是普通列车？");
        QUESTION_TEMPLATES.put("ticket_count", "请问需要买几张票？");
        QUESTION_TEMPLATES.put("price_limit", "请问您的预算大概多少？");
        QUESTION_TEMPLATES.put("product_name", "请问您想查什么商品？");
        // 可默认槽位的确认问题
        QUESTION_TEMPLATES.put("confirm_departure_station", "我查到您常从{value}出发，请问这次还是从那里出发吗？");
        QUESTION_TEMPLATES.put("confirm_seat_type", "默认查询二等座，需要更改吗？");
        QUESTION_TEMPLATES.put("confirm_train_type", "默认查询高铁/动车，需要更改吗？");
    }

    /**
     * 生成澄清建议。
     *
     * @param intentCategory 意图分类
     * @param entities       已提取的实体
     * @param context        会话上下文（可选）
     * @return 澄清建议结果
     */
    public ClarificationAdvice generateAdvice(
            String intentCategory,
            Map<String, Object> entities,
            String context) {

        // 无意图 → 需要澄清整个意图
        if (intentCategory == null || intentCategory.isBlank()
                || "UNKNOWN".equals(intentCategory)) {
            return ClarificationAdvice.createIntentClarification(
                    "未能确定您想做什么，请告诉我您需要什么帮助？"
            );
        }

        // 低风险查询类意图 → 通常不需要追问
        if (isQueryOnly(intentCategory)) {
            return ClarificationAdvice.noClarification();
        }

        return null; // placeholder - will be refined
    }

    /**
     * 基于词槽分析结果生成澄清建议。
     *
     * @param intentCategory 意图分类
     * @param entities       已提取实体
     * @param slotAnalysis   词槽分析结果
     * @return 完整的澄清建议
     */
    public ClarificationAdvice generateFromSlotAnalysis(
            String intentCategory,
            Map<String, Object> entities,
            SlotStateMachine.SlotAnalysisResult slotAnalysis) {

        // 如果意图不明
        if (intentCategory == null || "UNKNOWN".equals(intentCategory)) {
            return ClarificationAdvice.createIntentClarification(
                    "您好，请问您需要查询订单、查看商品，还是需要其他帮助？"
            );
        }

        // 查询类意图不需要追问
        if (isQueryOnly(intentCategory)) {
            return ClarificationAdvice.noClarification();
        }

        // 有冲突 → 先处理冲突
        if (slotAnalysis.hasConflicts()) {
            List<String> conflictQuestions = new ArrayList<>();
            for (Map<String, Object> conflict : slotAnalysis.conflicts()) {
                String reason = (String) conflict.get("reason");
                String slot1 = (String) conflict.get("slot1");
                String slot2 = (String) conflict.get("slot2");
                String q = String.format("您的要求有矛盾：%s。请问以哪个为准？（%s 还是 %s）",
                        reason, getSlotLabel(slot1), getSlotLabel(slot2));
                conflictQuestions.add(q);
            }
            return new ClarificationAdvice(true, "词槽矛盾", conflictQuestions,
                    slotAnalysis.missingSlots(), Collections.emptyList());
        }

        // 有必填缺失 → 按优先级追问
        if (slotAnalysis.hasMissing()) {
            List<String> questions = new ArrayList<>();
            for (String slot : slotAnalysis.missingSlots()) {
                questions.add(getQuestion(slot));
            }
            return new ClarificationAdvice(true, "信息不完整", questions,
                    slotAnalysis.missingSlots(), slotAnalysis.defaultableSlots());
        }

        // 有可默认槽位 → 确认
        if (slotAnalysis.hasDefaultable()) {
            List<String> confirmQuestions = new ArrayList<>();
            for (String slot : slotAnalysis.defaultableSlots()) {
                confirmQuestions.add(getConfirmQuestion(slot, entities));
            }
            return new ClarificationAdvice(true, "需要确认默认值", confirmQuestions,
                    Collections.emptyList(), slotAnalysis.defaultableSlots());
        }

        return ClarificationAdvice.noClarification();
    }

    // ==================== 内部方法 ====================

    private boolean isQueryOnly(String intentCategory) {
        if (intentCategory == null) return false;
        String lower = intentCategory.toLowerCase();
        return lower.contains("查") || lower.contains("query")
                || lower.contains("search") || lower.contains("get")
                || intentCategory.equals("GENERAL");
    }

    private String getSlotLabel(String slotName) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("departure_station", "出发站");
        labels.put("arrival_station", "到达站");
        labels.put("departure_date", "出发日期");
        labels.put("departure_time", "出发时间");
        labels.put("passenger", "乘车人");
        labels.put("seat_type", "座位类型");
        labels.put("order_id", "订单号");
        labels.put("product_name", "商品名称");
        labels.put("price_limit", "价格");
        labels.put("ticket_count", "票数");
        return labels.getOrDefault(slotName, slotName);
    }

    private String getQuestion(String slotName) {
        return QUESTION_TEMPLATES.getOrDefault(slotName,
                "请问您能提供" + getSlotLabel(slotName) + "吗？");
    }

    private String getConfirmQuestion(String slotName, Map<String, Object> entities) {
        // 对出发站等可默认槽位，尝试从上下文提取默认值
        String template = QUESTION_TEMPLATES.get("confirm_" + slotName);
        if (template != null && template.contains("{value}")) {
            // 尝试从 entities 或历史获取默认值
            String defaultValue = "常用地址";
            if (entities != null && entities.containsKey("location")) {
                defaultValue = entities.get("location").toString();
            }
            return template.replace("{value}", defaultValue);
        }
        return QUESTION_TEMPLATES.getOrDefault("confirm_" + slotName,
                "默认" + getSlotLabel(slotName) + "，需要更改吗？");
    }

    // ==================== 内部类 ====================

    /**
     * 澄清建议结果。
     *
     * @param needsClarification  是否需要澄清
     * @param reason              澄清原因
     * @param questions           追问问题列表
     * @param prioritySlots       追问优先级排序的槽位
     * @param defaultableSlots    可默认槽位
     */
    public record ClarificationAdvice(
            boolean needsClarification,
            String reason,
            List<String> questions,
            List<String> prioritySlots,
            List<String> defaultableSlots
    ) {
        /** 不需要澄清 */
        public static ClarificationAdvice noClarification() {
            return new ClarificationAdvice(false, "无需追问",
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        /** 需要澄清主意图 */
        public static ClarificationAdvice createIntentClarification(String question) {
            return new ClarificationAdvice(true, "意图不明确",
                    List.of(question), Collections.emptyList(), Collections.emptyList());
        }
    }
}
