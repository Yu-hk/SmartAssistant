/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import com.example.smartassistant.routing.contract.WorkflowOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic allowlist for semantic answer caching. */
@Component
public class SemanticAnswerCachePolicy {

    private static final Set<WorkflowOperation> BUSINESS_DOCUMENT_OPERATIONS = Set.of(
            WorkflowOperation.ANSWER,
            WorkflowOperation.EXPLAIN_ORDER_REQUIREMENTS,
            WorkflowOperation.EXPLAIN_ORDER_LIFECYCLE);

    public Decision resolve(TaskAnalysisResult analysis) {
        if (analysis == null || analysis.isNeedsClarification()
                || analysis.getSubIntents() == null || analysis.getSubIntents().isEmpty()) {
            return Decision.none("missing_or_clarification");
        }

        Set<WorkflowOperation> operations = new LinkedHashSet<>();
        for (Map<String, Object> node : analysis.getSubIntents()) {
            WorkflowOperation operation = WorkflowOperation.fromCode(stringValue(node.get("operation")))
                    .orElse(null);
            String accessMode = stringValue(node.get("access_mode"));
            boolean approval = booleanValue(node.get("human_approval_required"));
            if (operation == null || operation.isWrite()
                    || "WRITE".equalsIgnoreCase(accessMode) || approval) {
                return Decision.none("unsafe_or_unknown_operation");
            }
            operations.add(operation);
        }

        boolean allProduct = operations.stream()
                .allMatch(operation -> operation.domain() == WorkflowOperation.Domain.PRODUCT);
        if (allProduct) {
            boolean volatileCatalog = operations.stream().anyMatch(operation -> Set.of(
                    WorkflowOperation.DISCOVER_PRODUCTS,
                    WorkflowOperation.QUERY_HOT_PRODUCTS,
                    WorkflowOperation.ANALYZE_PRODUCT_DATA,
                    WorkflowOperation.RECOMMEND_PRODUCT).contains(operation));
            return new Decision(Scope.PRODUCT_CONSULTATION, volatileCatalog, "product_read_only");
        }

        String declared = analysis.getSemanticCacheCategory() == null
                ? "NONE" : analysis.getSemanticCacheCategory().trim().toUpperCase(Locale.ROOT);
        boolean allDocumentConsultation = operations.stream().allMatch(BUSINESS_DOCUMENT_OPERATIONS::contains);
        if ("BUSINESS_CONSULTATION".equals(declared) && allDocumentConsultation) {
            return new Decision(Scope.BUSINESS_CONSULTATION, false, "document_bound_read_only");
        }
        return Decision.none("not_allowlisted");
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool
                : value != null && Boolean.parseBoolean(value.toString());
    }

    public enum Scope {
        PRODUCT_CONSULTATION,
        BUSINESS_CONSULTATION,
        NONE
    }

    public record Decision(Scope scope, boolean volatileProductData, String reason) {
        public static Decision none(String reason) {
            return new Decision(Scope.NONE, false, reason);
        }

        public boolean cacheable() {
            return scope != Scope.NONE;
        }
    }
}
