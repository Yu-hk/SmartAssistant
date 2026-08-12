/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.quality;

import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.eval.FaithfulnessGuard;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, fail-closed checks for order-domain answers. */
@Component
public class OrderDomainQualityValidator {

    private static final Pattern ORDER_ID = Pattern.compile("(?i)\\bORD-[A-Z0-9-]+\\b");
    private static final Pattern SUCCESS_CLAIM = Pattern.compile(
            "已(?:成功)?(?:退款|取消|创建|下单|支付)|(?:退款|取消|下单|支付)成功|订单已创建");
    private static final List<String> ORDER_STATUSES = List.of(
            "待付款", "待支付", "已付款", "已支付", "待发货", "已发货",
            "已签收", "已完成", "已取消", "退款中", "已退款", "退款成功");

    private final OrderDataProvider orderData;

    /** Backward-compatible constructor for isolated controller/unit-test usage. */
    public OrderDomainQualityValidator() {
        this(null);
    }

    @Autowired
    public OrderDomainQualityValidator(OrderDataProvider orderData) {
        this.orderData = orderData;
    }

    public DomainQualityResult evaluate(String question, String answer, IntentType intent,
                                        String userId, RetrievalQualityResult retrieval,
                                        FaithfulnessGuard.FaithfulnessVerdict faithfulness) {
        if (retrieval != null && retrieval.isRejected()) {
            return DomainQualityResult.pass(1.0, "SAFE_NO_EVIDENCE_RESPONSE");
        }
        if (answer == null || answer.isBlank()) {
            return DomainQualityResult.fail("EMPTY_ORDER_ANSWER");
        }

        String context = retrieval != null ? retrieval.getContent() : null;
        if (requiresOrderEvidence(intent) && (context == null || context.isBlank())) {
            return DomainQualityResult.fail("MISSING_ORDER_EVIDENCE");
        }

        Set<String> questionIds = extractOrderIds(question);
        Set<String> contextIds = extractOrderIds(context);
        Set<String> answerIds = extractOrderIds(answer);
        Set<String> allowedIds = new LinkedHashSet<>(questionIds);
        allowedIds.addAll(contextIds);

        // CREATE_ORDER is the one action where a valid answer must introduce a brand-new
        // order ID that cannot exist in the question or pre-action retrieval context.
        // Trust it only after verifying that the persisted order belongs to this user.
        Set<String> persistedCreateStatuses = new LinkedHashSet<>();
        if (intent == IntentType.CREATE_ORDER) {
            for (String answerId : answerIds) {
                OrderDTO persisted = findOwnedOrder(answerId, userId);
                if (persisted != null) {
                    allowedIds.add(answerId);
                    persistedCreateStatuses.addAll(findStatuses(persisted.getStatus()));
                }
            }
        }

        if (!questionIds.isEmpty() && !contextIds.containsAll(questionIds)) {
            return DomainQualityResult.fail("ORDER_EVIDENCE_ID_MISMATCH");
        }
        if (!allowedIds.containsAll(answerIds)) {
            return DomainQualityResult.fail("ANSWER_ORDER_ID_MISMATCH");
        }

        Set<String> evidenceStatuses = findStatuses(context);
        evidenceStatuses.addAll(persistedCreateStatuses);
        Set<String> answerStatuses = findStatuses(answer);
        if (!evidenceStatuses.isEmpty() && !answerStatuses.isEmpty()
                && !evidenceStatuses.containsAll(answerStatuses)) {
            return DomainQualityResult.fail("ORDER_STATUS_MISMATCH");
        }

        if (faithfulness != null && faithfulness.checked() && faithfulness.hallucination()) {
            // The generic rule-based guard is intentionally non-blocking. At this point
            // order IDs and statuses have already passed deterministic evidence checks,
            // so a generic number/entity warning must not suppress an otherwise valid
            // order response. Hard failures above remain fail-closed.
            return DomainQualityResult.warn(
                    Math.max(0.55, 1.0 - faithfulness.score()),
                    isActionIntent(intent)
                            ? "ACTION_RESULT_REQUIRES_TOOL_EVIDENCE"
                            : "UNSUPPORTED_ORDER_FACTS");
        }

        if (isActionIntent(intent) && SUCCESS_CLAIM.matcher(answer).find()) {
            return DomainQualityResult.warn(0.55, "ACTION_RESULT_REQUIRES_TOOL_EVIDENCE");
        }
        if (requiresIdentity(intent) && (userId == null || userId.isBlank() || "null".equals(userId))) {
            return DomainQualityResult.warn(0.6, "ORDER_IDENTITY_NOT_CONFIRMED");
        }

        double score = retrieval != null ? retrieval.getNormalizedScore() : 0.75;
        return DomainQualityResult.pass(Math.max(0.7, score), "ORDER_FACTS_VERIFIED");
    }

    private OrderDTO findOwnedOrder(String orderId, String userId) {
        if (orderData == null || orderId == null || userId == null || userId.isBlank()) {
            return null;
        }
        OrderDTO order = orderData.findOrderByOrderId(orderId);
        if (order == null || order.getUserId() == null) {
            return null;
        }
        return String.valueOf(order.getUserId()).equals(userId.trim()) ? order : null;
    }

    private static boolean requiresOrderEvidence(IntentType intent) {
        return intent == IntentType.QUERY_ORDER || intent == IntentType.REFUND || intent == IntentType.CANCEL;
    }

    private static boolean requiresIdentity(IntentType intent) {
        return requiresOrderEvidence(intent);
    }

    private static boolean isActionIntent(IntentType intent) {
        return intent == IntentType.CREATE_ORDER || intent == IntentType.REFUND || intent == IntentType.CANCEL;
    }

    private static Set<String> extractOrderIds(String value) {
        Set<String> ids = new LinkedHashSet<>();
        if (value == null) return ids;
        Matcher matcher = ORDER_ID.matcher(value);
        while (matcher.find()) {
            ids.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return ids;
    }

    private static Set<String> findStatuses(String value) {
        Set<String> statuses = new LinkedHashSet<>();
        if (value == null) return statuses;
        for (String status : ORDER_STATUSES) {
            if (value.contains(status)) statuses.add(status);
        }
        return statuses;
    }
}
