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
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** Lightweight factual consistency checks for product-domain answers. */
@Component
public class ProductDomainQualityValidator {

    private static final Pattern FACTUAL_CLAIM = Pattern.compile(
            "(?:￥|¥|\\d+(?:\\.\\d+)?(?:元|GB|TB|英寸|Hz|W|mAh|%))|库存|现货|缺货|价格|型号|规格",
            Pattern.CASE_INSENSITIVE);

    public DomainQualityResult evaluate(String answer, RetrievalQualityResult retrieval,
                                        FaithfulnessGuard.FaithfulnessVerdict faithfulness) {
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
