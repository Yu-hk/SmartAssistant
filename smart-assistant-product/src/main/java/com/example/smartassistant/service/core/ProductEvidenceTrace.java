package com.example.smartassistant.service.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Internal evidence manifest. No question, history, profile, model reasoning or secrets. */
public final class ProductEvidenceTrace {
    private ProductEvidenceTrace() { }

    public static Map<String, Object> catalog(String requestId, MultiProductQueryPlan plan,
            List<Map<String, Object>> evidence, ProductFieldCoverage.Report coverage, List<Integer> quantities) {
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (Map<String, Object> row : evidence) {
            if (!"RESOLVED".equals(row.get("status"))) continue;
            if (!(row.get("fields") instanceof Map<?, ?> fields)) continue;
            for (MultiProductQueryPlan.Field field : MultiProductQueryPlan.Field.values()) {
                if (!(fields.get(field.name()) instanceof Map<?, ?> fact)) continue;
                boolean known = Boolean.TRUE.equals(fact.get("known"));
                if (!known && !Boolean.FALSE.equals(fact.get("known"))) continue;
                String id = row.get("productCode") + ":" + field.name() + (known ? "" : ":UNVERIFIED");
                String text = context(row, field.name(), fact);
                contexts.add(Map.of("id", id, "sha256", hash(text), "state", known ? "KNOWN" : "UNKNOWN",
                        "source", "catalog_field", "rank", contexts.size() + 1));
            }
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("version", 1);
        trace.put("requestId", safeId(requestId));
        trace.put("route", "CATALOG_FIELDS");
        trace.put("modelCalled", false);
        trace.put("contextKind", "catalog_field_evidence");
        trace.put("coverage", coverage.toMap());
        trace.put("finalEvidence", List.copyOf(contexts));
        trace.put("arithmetic", Map.of("relation", plan.relation().name(), "quantities", List.copyOf(quantities),
                "method", "BigDecimal", "priceScope", "catalog_unit_price_only",
                "shippingAndDiscountsVerified", false));
        trace.put("executeRetry", false);
        return Collections.unmodifiableMap(trace);
    }

    public static String context(Map<?, ?> row, String field, Map<?, ?> fact) {
        String evidence = Boolean.TRUE.equals(fact.get("known")) ? String.valueOf(fact.get("evidence"))
                : field + "：known=false，资料未核实，无具体数值证据";
        return row.get("productName") + "，本问题数量 " + row.get("quantity") + " 件；" + evidence;
    }

    public static String safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9._:-]{1,128}") ? id : "unavailable";
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
