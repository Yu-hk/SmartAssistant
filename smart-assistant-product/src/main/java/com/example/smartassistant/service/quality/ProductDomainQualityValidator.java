/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.quality;

import com.example.smartassistant.common.error.CustomerMessages;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.eval.FaithfulnessGuard;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/** Lightweight factual consistency checks for product-domain answers. */
@Component
public class ProductDomainQualityValidator {

    private static final Pattern FACTUAL_CLAIM = Pattern.compile(
            "(?:￥|¥|\\d+(?:\\.\\d+)?(?:元|GB|TB|英寸|Hz|W|mAh|%))|库存|现货|缺货|价格|型号|规格",
            Pattern.CASE_INSENSITIVE);

    // Match the executor's complete recovery replies, not uncertainty words such
    // as "暂时无法" or "信息不足" in a legitimate knowledge-grounded answer.
    private static final Set<String> OPERATIONAL_RECOVERY_REPLIES = Set.of(
            "检测到 Agent 报告被阻塞，无法继续。请提供更多信息或重新描述需求。",
            "检测到基础设施故障，已暂停以避免持续重试。请稍后再试。",
            "循环守卫暂停",
            CustomerMessages.UNAVAILABLE);

    public DomainQualityResult evaluate(String answer, RetrievalQualityResult retrieval,
                                        FaithfulnessGuard.FaithfulnessVerdict faithfulness) {
        return evaluate(answer, retrieval, faithfulness, false);
    }

    public DomainQualityResult evaluate(String answer, RetrievalQualityResult retrieval,
                                        FaithfulnessGuard.FaithfulnessVerdict faithfulness,
                                        boolean hasToolEvidence) {
        // An operational refusal contains no factual claims, but that does not make it
        // a faithful answer. Check before evidence/faithfulness shortcuts.
        String normalizedAnswer = answer == null ? "" : answer.strip();
        if (normalizedAnswer.contains("您的输入已被内容安全策略拦截")
                || normalizedAnswer.startsWith("处理失败:") || normalizedAnswer.startsWith("处理失败：")
                || normalizedAnswer.startsWith("❌")
                || OPERATIONAL_RECOVERY_REPLIES.contains(normalizedAnswer)) {
            return DomainQualityResult.fail("PRODUCT_EXECUTION_FAILURE");
        }
        if (retrieval != null && retrieval.isRejected()) {
            return DomainQualityResult.pass(1.0, "SAFE_NO_EVIDENCE_RESPONSE");
        }
        if (answer == null || answer.isBlank()) {
            return DomainQualityResult.fail("EMPTY_PRODUCT_ANSWER");
        }
        if (faithfulness != null && faithfulness.checked() && faithfulness.hallucination()) {
            return DomainQualityResult.warn(
                    Math.max(0.1, 1.0 - faithfulness.score()),
                    "UNSUPPORTED_PRODUCT_CLAIMS");
        }
        if (hasToolEvidence && faithfulness != null && faithfulness.checked()) {
            return DomainQualityResult.pass(Math.max(0.7, 1.0 - faithfulness.score()),
                    "PRODUCT_TOOL_FACTS_VERIFIED");
        }
        if (retrieval == null || retrieval.getContent() == null || retrieval.getContent().isBlank()) {
            if (FACTUAL_CLAIM.matcher(answer).find()) {
                return DomainQualityResult.warn(0.4, "UNVERIFIED_PRODUCT_FACTS");
            }
            return DomainQualityResult.warn(0.6, "PRODUCT_EVIDENCE_UNAVAILABLE");
        }
        if (!retrieval.isHighQuality()) {
            return DomainQualityResult.warn(
                    retrieval.getNormalizedScore(), "LOW_PRODUCT_RETRIEVAL_QUALITY");
        }
        return DomainQualityResult.pass(
                Math.max(0.7, retrieval.getNormalizedScore()), "PRODUCT_FACTS_VERIFIED");
    }
}
