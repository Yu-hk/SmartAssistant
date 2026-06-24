/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.evaluation;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 意图评测编排服务——在 LLM 输出后运行规则层的评测后处理。
 * <p>
 * 覆盖 6 个缺失的评测维度：
 * <ul>
 *   <li>实体归一化（Entity Normalization）</li>
 *   <li>词槽缺失（Slot Missing）</li>
 *   <li>词槽冲突（Slot Conflict）</li>
 *   <li>词槽追问（Slot Follow-up）</li>
 *   <li>澄清判断（Clarification Judgment）</li>
 *   <li>输入鲁棒性（Input Robustness）</li>
 * </ul>
 * 多意图拆分、隐含意图已在 LLM Prompt 层增强实现。
 * </p>
 */
@Service
public class IntentEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(IntentEvaluationService.class);

    private final EntityNormalizer entityNormalizer;
    private final SlotStateMachine slotStateMachine;
    private final ClarificationService clarificationService;

    /** 置信度低于此阈值时触发澄清，而非直接执行 */
    @Value("${router.task-analysis.clarification-threshold:0.6}")
    private double clarificationThreshold;

    public IntentEvaluationService(EntityNormalizer entityNormalizer,
                                   SlotStateMachine slotStateMachine,
                                   ClarificationService clarificationService) {
        this.entityNormalizer = entityNormalizer;
        this.slotStateMachine = slotStateMachine;
        this.clarificationService = clarificationService;
    }

    /**
     * 对 LLM 任务分析结果执行规则层评测后处理。
     * <p>
     * 包括：输入鲁棒性分析、实体归一化、词槽状态追踪、冲突检测、澄清建议。
     * </p>
     *
     * @param question       用户原始输入
     * @param llmResult      LLM 产出的任务分析结果
     * @return 后处理增强后的任务分析结果
     */
    public TaskAnalysisResult postProcess(String question, TaskAnalysisResult llmResult) {
        if (llmResult == null) {
            llmResult = TaskAnalysisResult.empty();
        }

        long start = System.currentTimeMillis();

        // 1. 输入鲁棒性：纠错 + 标准化
        EntityNormalizer.NormalizationResult normalResult =
                entityNormalizer.normalizeInput(question);
        if (normalResult.hasCorrections()) {
            llmResult.setStandardizedInput(normalResult.getNormalizedText());
            llmResult.setInputCorrections(normalResult.getCorrections());
            llmResult.setNoiseTypes(normalResult.getNoiseTypes());
        }

        // 2. 实体归一化：对 entities 中的常见表达做归一
        if (llmResult.hasEntities()) {
            Map<String, String> normalizedEntities = new LinkedHashMap<>();
            List<Map<String, Object>> normDetails = new ArrayList<>();

            for (Map.Entry<String, Object> entry : llmResult.getEntities().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() != null ? entry.getValue().toString() : null;
                if (value == null || value.isBlank() || "null".equals(value)) continue;

                String normKey = normalizeKey(key);
                String normValue = value;

                // 日期归一化
                if (isDateField(key)) {
                    String normalizedDate = entityNormalizer.normalizeDate(value);
                    if (normalizedDate != null && !normalizedDate.equals(value)) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("field", key);
                        detail.put("original", value);
                        detail.put("normalized", normalizedDate);
                        detail.put("type", "date");
                        normDetails.add(detail);
                        normValue = normalizedDate;
                    }
                }

                // 金额归一化
                if (isAmountField(key)) {
                    Double normalizedAmount = entityNormalizer.normalizeAmount(value);
                    if (normalizedAmount != null) {
                        String amountStr = String.valueOf(normalizedAmount);
                        if (!amountStr.equals(value.replaceAll("[^\\d.]", ""))) {
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("field", key);
                            detail.put("original", value);
                            detail.put("normalized", amountStr);
                            detail.put("type", "amount");
                            normDetails.add(detail);
                        }
                        normValue = amountStr;
                    }
                }

                // 时间窗口归一化
                if (isTimeField(key)) {
                    String[] window = entityNormalizer.normalizeTimeWindow(value);
                    if (window != null) {
                        String windowStr = window[0] + "-" + window[1];
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("field", key);
                        detail.put("original", value);
                        detail.put("normalized", windowStr);
                        detail.put("type", "time_window");
                        normDetails.add(detail);
                    }
                }

                normalizedEntities.put(normKey, normValue);
            }

            if (!normalizedEntities.isEmpty()) {
                llmResult.setNormalizedEntities(normalizedEntities);
            }
            if (!normDetails.isEmpty()) {
                llmResult.setNormalizationDetails(normDetails);
            }
        }

        // 3. 词槽状态：填充/缺失/冲突
        SlotStateMachine.SlotAnalysisResult slotAnalysis =
                slotStateMachine.analyzeSlots(
                        llmResult.getIntentCategory(),
                        llmResult.getEntities());

        if (!slotAnalysis.filledSlots().isEmpty()) {
            llmResult.setFilledSlots(slotAnalysis.filledSlots());
        }
        if (slotAnalysis.hasMissing()) {
            llmResult.setMissingSlots(slotAnalysis.missingSlots());
            llmResult.setDefaultableSlots(slotAnalysis.defaultableSlots());
        }
        if (slotAnalysis.hasConflicts()) {
            llmResult.setSlotConflicts(slotAnalysis.conflicts());
        }

        // 4. 澄清判断：是否追问 + 追问问题
        //    触发条件：词槽缺失/冲突/可默认 + 置信度低于阈值
        boolean shouldClarify = slotAnalysis.hasMissing() || slotAnalysis.hasConflicts()
                || slotAnalysis.hasDefaultable();

        // 置信度低于阈值时也触发澄清（即使词槽完整）
        if (llmResult.isConfidenceLow(clarificationThreshold)) {
            shouldClarify = true;
            if (llmResult.getClarificationReason() == null) {
                llmResult.setClarificationReason(
                        String.format("意图置信度偏低(%.2f)，需澄清确认", llmResult.getConfidence()));
            }
        }

        if (shouldClarify) {
            ClarificationService.ClarificationAdvice advice =
                    clarificationService.generateFromSlotAnalysis(
                            llmResult.getIntentCategory(),
                            llmResult.getEntities(),
                            slotAnalysis);

            llmResult.setNeedsClarification(advice.needsClarification()
                    || llmResult.isConfidenceLow(clarificationThreshold));
            llmResult.setClarificationReason(advice.reason());
            llmResult.setClarificationQuestions(new ArrayList<>(advice.questions()));

            List<String> priority = new ArrayList<>();
            priority.addAll(advice.prioritySlots());
            if (!priority.isEmpty()) {
                llmResult.setClarificationSlotPriority(priority);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[IntentEvaluation] 后处理完成: corrections={}, normalized={}, missing={}, conflicts={}, confidence={}, cost={}ms",
                llmResult.getInputCorrections().size(),
                llmResult.getNormalizationDetails().size(),
                llmResult.getMissingSlots().size(),
                llmResult.getSlotConflicts().size(),
                String.format("%.2f", llmResult.getConfidence()),
                elapsed);

        return llmResult;
    }

    // ==================== 辅助方法 ====================

    private String normalizeKey(String key) {
        if (key == null) return "";
        return key.toLowerCase().replace("-", "_").replace(" ", "_");
    }

    private boolean isDateField(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return lower.contains("date") || lower.contains("time")
                || lower.contains("日期") || lower.contains("时间");
    }

    private boolean isAmountField(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return lower.contains("amount") || lower.contains("price")
                || lower.contains("money") || lower.contains("金额")
                || lower.contains("价格") || lower.contains("费用");
    }

    private boolean isTimeField(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return lower.contains("time") || lower.contains("时间")
                || (lower.contains("departure") && !lower.contains("date"))
                || lower.contains("arrival");
    }
}
