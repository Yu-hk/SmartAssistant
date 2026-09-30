package com.example.smartassistant.service.core;

import java.util.*;
import static com.example.smartassistant.service.core.MultiProductQueryPlan.Field;

/** Request-derived slots, not a similarity score. Unknown is terminal, missing is not. */
public final class ProductFieldCoverage {
    private ProductFieldCoverage() { }

    public record Slot(String requestedProduct, String productCode, String field, String state) { }
    public record Report(int requested, int known, int unknown, int missing, int unresolved,
                         List<Slot> slots) {
        public Report { slots = List.copyOf(slots); }
        public boolean complete() { return requested > 0 && missing == 0 && unresolved == 0; }
        public Map<String, Object> toMap() {
            return Map.of("version", 1, "requested", requested, "known", known, "unknown", unknown,
                    "missing", missing, "unresolved", unresolved, "complete", complete(), "slots", slots);
        }
    }

    public static Report inspect(MultiProductQueryPlan plan, ProductSemanticQueryPlan semantic,
                                 List<Map<String, Object>> evidence) {
        List<Slot> slots = new ArrayList<>();
        for (int index = 0; index < plan.products().size(); index++) {
            String name = plan.products().get(index);
            var task = semantic == null ? null : semantic.tasks().get(index);
            Set<Field> required = task == null ? plan.fields() : task.fields();
            String code = task == null ? "" : Objects.toString(task.entity().code(), "");
            String requestedCode = code;
            // Match the requested mention and verified identity, never a list position or a substring.
            List<Map<String, Object>> matches = evidence.stream()
                    .filter(row -> name.equals(row.get("requestedProduct")))
                    .filter(row -> requestedCode.isBlank() || requestedCode.equals(row.get("productCode"))).toList();
            Map<String, Object> row = matches.size() == 1 ? matches.getFirst() : Map.of();
            boolean resolved = "RESOLVED".equals(row.get("status"))
                    && row.get("productCode") instanceof String actual && !actual.isBlank();
            if (resolved) code = String.valueOf(row.get("productCode"));
            Map<?, ?> fields = row.get("fields") instanceof Map<?, ?> values ? values : Map.of();
            for (Field field : Field.values()) {
                if (!required.contains(field)) continue;
                Object value = fields.get(field.name());
                String state = "MISSING";
                if (!resolved) state = "UNRESOLVED";
                else if (value instanceof Map<?, ?> fact) {
                    if (Boolean.TRUE.equals(fact.get("known"))
                            && fact.get("evidence") instanceof String text && !text.isBlank()) state = "KNOWN";
                    else if (Boolean.FALSE.equals(fact.get("known"))
                            && "".equals(fact.get("evidence"))) state = "UNKNOWN";
                }
                slots.add(new Slot(name, code, field.name(), state));
            }
        }
        return new Report(slots.size(), count(slots, "KNOWN"), count(slots, "UNKNOWN"),
                count(slots, "MISSING"), count(slots, "UNRESOLVED"), slots);
    }

    private static int count(List<Slot> slots, String state) {
        return (int) slots.stream().filter(slot -> state.equals(slot.state())).count();
    }
}
