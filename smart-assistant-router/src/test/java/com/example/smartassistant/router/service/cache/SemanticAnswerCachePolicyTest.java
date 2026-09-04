package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.router.model.TaskAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticAnswerCachePolicyTest {

    private final SemanticAnswerCachePolicy policy = new SemanticAnswerCachePolicy();

    @Test
    void cachesReadOnlyProductConsultationAndMarksHotDataVolatile() {
        TaskAnalysisResult analysis = analysis("NONE",
                node("QUERY_HOT_PRODUCTS", "READ", false));

        SemanticAnswerCachePolicy.Decision decision = policy.resolve(analysis);

        assertThat(decision.scope())
                .isEqualTo(SemanticAnswerCachePolicy.Scope.PRODUCT_CONSULTATION);
        assertThat(decision.volatileProductData()).isTrue();
    }

    @Test
    void cachesOnlyExplicitDocumentBoundBusinessConsultation() {
        TaskAnalysisResult business = analysis("BUSINESS_CONSULTATION",
                node("ANSWER", "READ", false));
        TaskAnalysisResult general = analysis("NONE",
                node("ANSWER", "READ", false));

        assertThat(policy.resolve(business).scope())
                .isEqualTo(SemanticAnswerCachePolicy.Scope.BUSINESS_CONSULTATION);
        assertThat(policy.resolve(general).cacheable()).isFalse();
    }

    @Test
    void rejectsOrderQueriesWritesApprovalsAndMixedIntents() {
        assertThat(policy.resolve(analysis("NONE",
                node("QUERY_ORDER", "READ", false))).cacheable()).isFalse();
        assertThat(policy.resolve(analysis("PRODUCT_CONSULTATION",
                node("CREATE_ORDER", "WRITE", true))).cacheable()).isFalse();
        assertThat(policy.resolve(analysis("PRODUCT_CONSULTATION",
                node("QUERY_PRODUCT", "READ", false),
                node("QUERY_ORDER", "READ", false))).cacheable()).isFalse();
    }

    private static TaskAnalysisResult analysis(String category, Map<String, Object>... nodes) {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setSemanticCacheCategory(category);
        result.setSubIntents(List.of(nodes));
        return result;
    }

    private static Map<String, Object> node(String operation, String access, boolean approval) {
        return Map.of(
                "operation", operation,
                "access_mode", access,
                "human_approval_required", approval);
    }
}
