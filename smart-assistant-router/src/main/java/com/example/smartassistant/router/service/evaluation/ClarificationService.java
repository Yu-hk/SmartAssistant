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

        return ClarificationAdvice.noClarification();
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
                    "请说明您希望完成的操作，以及必要的对象或约束。"
            );
        }

        // 有冲突 → 先处理冲突
        if (slotAnalysis.hasConflicts()) {
            List<String> conflictQuestions = new ArrayList<>();
            for (Map<String, Object> conflict : slotAnalysis.conflicts()) {
                String reason = (String) conflict.get("reason");
                String slot1 = (String) conflict.get("slot1");
                String slot2 = (String) conflict.get("slot2");
                String q = String.format("您的要求有矛盾：%s。请问以哪个为准？（%s 还是 %s）",
                        reason, getSlotLabel(slot1, slotAnalysis.slotDefs()),
                        getSlotLabel(slot2, slotAnalysis.slotDefs()));
                conflictQuestions.add(q);
            }
            return new ClarificationAdvice(true, "词槽矛盾", conflictQuestions,
                    slotAnalysis.missingSlots(), Collections.emptyList());
        }

        // 有必填缺失 → 按优先级追问
        if (slotAnalysis.hasMissing()) {
            List<String> questions = new ArrayList<>();
            for (String slot : slotAnalysis.missingSlots()) {
                questions.add(getQuestion(slot, slotAnalysis.slotDefs()));
            }
            return new ClarificationAdvice(true, "信息不完整", questions,
                    slotAnalysis.missingSlots(), slotAnalysis.defaultableSlots());
        }

        // 有可默认槽位 → 确认
        if (slotAnalysis.hasDefaultable()) {
            List<String> confirmQuestions = new ArrayList<>();
            for (String slot : slotAnalysis.defaultableSlots()) {
                confirmQuestions.add(getConfirmQuestion(slot, entities, slotAnalysis.slotDefs()));
            }
            return new ClarificationAdvice(true, "需要确认默认值", confirmQuestions,
                    Collections.emptyList(), slotAnalysis.defaultableSlots());
        }

        return ClarificationAdvice.noClarification();
    }

    // ==================== 内部方法 ====================

    private String getSlotLabel(String slotName, List<SlotStateMachine.SlotDef> definitions) {
        return findDefinition(slotName, definitions).map(SlotStateMachine.SlotDef::description)
                .filter(label -> !label.isBlank()).orElse(slotName);
    }

    private String getQuestion(String slotName, List<SlotStateMachine.SlotDef> definitions) {
        return findDefinition(slotName, definitions).map(SlotStateMachine.SlotDef::question)
                .filter(question -> !question.isBlank())
                .orElse("请提供" + getSlotLabel(slotName, definitions) + "。");
    }

    private String getConfirmQuestion(String slotName, Map<String, Object> entities,
                                      List<SlotStateMachine.SlotDef> definitions) {
        return "将使用默认的" + getSlotLabel(slotName, definitions) + "，需要更改吗？";
    }

    private Optional<SlotStateMachine.SlotDef> findDefinition(
            String slotName, List<SlotStateMachine.SlotDef> definitions) {
        if (definitions == null) return Optional.empty();
        return definitions.stream().filter(def -> Objects.equals(slotName, def.name())).findFirst();
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
