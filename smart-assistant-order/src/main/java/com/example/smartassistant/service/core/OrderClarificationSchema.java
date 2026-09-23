package com.example.smartassistant.service.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Trusted order-domain metadata. Unknown operations and fields fail closed. */
final class OrderClarificationSchema {
    private static final Set<String> OPERATIONS = Set.of("CREATE_ORDER", "TRACK_LOGISTICS",
            "QUERY_PAYMENT_PENDING", "CANCEL_ORDER", "REFUND_ORDER", "APPLY_AFTER_SALES");
    private static final Set<String> FIELDS = Set.of("product", "recipientName", "recipientPhone",
            "shippingAddress", "orderNumber", "reason", "afterSalesType");
    private static final OrderClarificationSchema DEFAULT = load();
    private final Properties properties;

    OrderClarificationSchema(Properties properties) {
        this.properties = properties;
        for (String operation : OPERATIONS) {
            List<String> required = values("operation." + operation + ".required");
            if (required.stream().anyMatch(field -> !FIELDS.contains(field)))
                throw new IllegalStateException("Unknown required order field for " + operation);
        }
        for (String field : FIELDS) {
            if (!values("field." + field + ".aliases").contains(field))
                throw new IllegalStateException("Canonical order field missing from aliases: " + field);
            value("field." + field + ".label");
        }
        values("field.afterSalesType.allowed");
    }
    static OrderClarificationSchema defaultSchema() { return DEFAULT; }
    List<String> required(String operation) {
        return OPERATIONS.contains(operation) ? values("operation." + operation + ".required") : null;
    }
    List<String> aliases(String field) { return values("field." + field + ".aliases"); }
    String label(String field) { return value("field." + field + ".label"); }
    boolean allowedAfterSalesType(String value) {
        return values("field.afterSalesType.allowed").contains(value);
    }

    private String value(String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Missing order schema key: " + key);
        return raw.trim();
    }
    private List<String> values(String key) {
        return Arrays.stream(value(key).split(",")).map(String::trim)
                .filter(item -> !item.isBlank()).distinct().toList();
    }
    private static OrderClarificationSchema load() {
        Properties properties = new Properties();
        try (var input = OrderClarificationSchema.class.getResourceAsStream("/order-clarification-schema.properties")) {
            if (input == null) throw new IllegalStateException("Missing order clarification schema");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return new OrderClarificationSchema(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load order clarification schema", exception);
        }
    }
}
