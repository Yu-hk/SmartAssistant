package com.example.smartassistant.common.agent.protocol;

import java.util.*;

/** Domain-produced missing input, never inferred from the final merged answer. */
public record ClarificationRequest(String domain, String operation, List<String> fields) {
    public static final String DATA_KEY = "clarificationRequest";
    private static final Map<String, Set<String>> SCOPES = Map.ofEntries(
            Map.entry("product:QUERY_PRODUCT", Set.of("product")),
            Map.entry("product:DISCOVER_PRODUCTS", Set.of("product", "weight", "budget")),
            Map.entry("order:CREATE_ORDER", Set.of("product", "recipientName", "recipientPhone", "shippingAddress")),
            Map.entry("order:QUERY_ORDER", Set.of("orderNumber")),
            Map.entry("order:TRACK_LOGISTICS", Set.of("orderNumber")),
            Map.entry("order:QUERY_PAYMENT_PENDING", Set.of("orderNumber")),
            Map.entry("order:CANCEL_ORDER", Set.of("orderNumber", "reason")),
            Map.entry("order:REFUND_ORDER", Set.of("orderNumber", "reason")),
            Map.entry("order:APPLY_AFTER_SALES", Set.of("orderNumber", "reason", "afterSalesType")));

    public ClarificationRequest {
        var allowed = SCOPES.get(domain + ":" + operation);
        if (allowed == null || fields == null || fields.isEmpty() || fields.size() > 6
                || new HashSet<>(fields).size() != fields.size() || !allowed.containsAll(fields))
            throw new IllegalArgumentException("Invalid domain clarification scope");
        fields = List.copyOf(fields);
    }

    public static ClarificationRequest read(Object raw) {
        try {
            if (raw instanceof ClarificationRequest value) return value;
            if (!(raw instanceof Map<?, ?> map) || map.size() != 3
                    || !(map.get("domain") instanceof String domain)
                    || !(map.get("operation") instanceof String operation)
                    || !(map.get("fields") instanceof List<?> fields)
                    || fields.stream().anyMatch(field -> !(field instanceof String))) return null;
            return new ClarificationRequest(domain, operation, fields.stream().map(String.class::cast).toList());
        } catch (IllegalArgumentException invalid) { return null; }
    }

    public Map<String, Object> toMap() { return Map.of("domain", domain, "operation", operation, "fields", fields); }
}
