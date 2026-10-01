package com.example.smartassistant.service.search;

import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.pipeline.RagSearchContext;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.service.core.ProductEvidenceTrace;
import java.util.*;

/** Evidence manifests for offline evaluation. Never equates RRF with recall or judge quality. */
public final class ProductRetrievalDiagnostics {
    private ProductRetrievalDiagnostics() { }

    public static RetrievalQualityResult catalog(AgentExecutionResponse response, int maxItems) {
        if (!(response.data().get("fieldCoverage") instanceof Map<?, ?> coverage)) return null;
        if (!(response.data().get("productEvidence") instanceof List<?> rows)) return null;
        List<RagSearchContext.RankedItem> items = new ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row) || !"RESOLVED".equals(row.get("status"))) continue;
            if (!(row.get("fields") instanceof Map<?, ?> fields)) continue;
            for (var field : com.example.smartassistant.service.core.MultiProductQueryPlan.Field.values()) {
                if (fields.get(field.name()) instanceof Map<?, ?> fact) {
                    items.add(new RagSearchContext.RankedItem(ProductEvidenceTrace.context(row, field.name(), fact), 1));
                }
            }
        }
        boolean complete = Boolean.TRUE.equals(coverage.get("complete"))
                && !Boolean.TRUE.equals(response.data().get("clarificationRequired"));
        boolean fits = !items.isEmpty() && items.size() <= maxItems;
        // An incomplete requirement set cannot become high-quality merely by a high top-1 score.
        RetrievalQualityResult result = complete && fits
                ? RetrievalQualityResult.highQuality(EvidenceContextFormatter.format(items, List.of("catalog-fields"), 1, maxItems).content(), 1)
                : RetrievalQualityResult.insufficientEvidence("", 0, "请求的商品字段证据尚不完整");
        Map<String, Object> trace = new LinkedHashMap<>();
        if (response.data().get("evidenceTrace") instanceof Map<?, ?> manifest)
            manifest.forEach((key, value) -> { if (key instanceof String name) trace.put(name, value); });
        trace.put("contextKind", "catalog_field_context");
        trace.put("coverage", coverage);
        trace.put("evidenceBudgetExceeded", items.size() > maxItems);
        trace.put("contextSha256", ProductEvidenceTrace.hash(result.getContent()));
        trace.put("finalContextCount", fits && complete ? items.size() : 0);
        result.setDiagnostics(trace);
        return result;
    }

    public static Map<String, Object> pipeline(String requestId, List<RagSearchContext> attempts,
                                               RagSearchContext selected, String content, int maxItems) {
        List<Map<String, Object>> rounds = new ArrayList<>();
        for (RagSearchContext attempt : attempts) {
            List<Map<String, Object>> paths = new ArrayList<>();
            for (var path : attempt.getPathResults().values()) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (String text : path.getItems().stream().limit(64).toList()) {
                    items.add(Map.of("id", RagSearchContext.evidenceId(text), "rank", items.size() + 1));
                }
                paths.add(Map.of("path", path.getPathName(), "items", List.copyOf(items),
                        "totalCandidates", path.getItems().size()));
            }
            Map<String, Object> round = new LinkedHashMap<>();
            round.put("attempt", rounds.size() + 1);
            round.put("paths", paths);
            round.put("stages", attempt.getStageSnapshots());
            round.put("qualityScoreKind", "top1_rrf_agreement_not_recall");
            round.put("qualityScore", attempt.getQualityScore());
            for (String key : List.of("adaptive.sparseWeight", "adaptive.denseWeight", "rag.rerankFusionWeight"))
                if (attempt.getAttribute(key) instanceof Number number) round.put(key, number);
            rounds.add(round);
        }
        List<Map<String, Object>> finalItems = new ArrayList<>();
        for (var item : selected.getFusedResults().stream().limit(maxItems).toList()) {
            finalItems.add(Map.of("id", RagSearchContext.evidenceId(item.getContent()), "rank", finalItems.size() + 1));
        }
        Map<String, Object> diagnostic = new LinkedHashMap<>(Map.of("version", 1, "requestId", ProductEvidenceTrace.safeId(requestId),
                "route", "RAG_PIPELINE", "contextKind", "rag_formatted_context", "attempts", rounds,
                "selectedAttempt", attempts.indexOf(selected) + 1, "finalEvidence", finalItems,
                "contextSha256", ProductEvidenceTrace.hash(content), "executeRetry", attempts.size() > 1));
        if (selected.getAttribute("rag.feedback") instanceof Map<?, ?> feedback) {
            diagnostic.put("feedback", feedback);
            diagnostic.put("executeRetry", attempts.size() > 1 || Boolean.TRUE.equals(feedback.get("attempted")));
        }
        return diagnostic;
    }
}
