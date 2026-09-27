package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.OrderShippingAddressPolicy;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only, owner-scoped checkout suggestions. Never writes to an order or profile. */
@Service
public class OrderCheckoutHistoryService {
    private static final int SCAN_LIMIT = 20;
    private static final int SUGGESTION_LIMIT = 3;
    private final JdbcTemplate jdbc;

    public OrderCheckoutHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Suggestion(String recipientName, String recipientPhone,
                             String shippingAddress, LocalDateTime orderDate) { }
    public record Suggestions(List<Suggestion> options, boolean conflictingHistory) { }

    public Suggestions suggestions(Long ownerId) {
        if (ownerId == null || ownerId <= 0) return new Suggestions(List.of(), false);
        List<Suggestion> rows = jdbc.query("""
                SELECT contact_name, contact_phone, shipping_address, created_at
                  FROM orders
                 WHERE user_id = ?
                   AND status IN ('待发货', '已发货', '已签收', '已完成')
                   AND NOT EXISTS (SELECT 1 FROM profile_lifecycle p
                                    WHERE p.user_id = ? AND p.analysis_enabled = false)
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """, (rs, index) -> new Suggestion(rs.getString("contact_name"),
                rs.getString("contact_phone"), rs.getString("shipping_address"),
                rs.getTimestamp("created_at") == null ? null
                        : rs.getTimestamp("created_at").toLocalDateTime()), ownerId, ownerId, SCAN_LIMIT);
        List<Suggestion> options = new ArrayList<>();
        Set<List<String>> seen = new LinkedHashSet<>();
        for (Suggestion row : rows) {
            if (!usable(row)) continue;
            if (seen.add(List.of(row.recipientName().strip(), row.recipientPhone().strip(),
                    row.shippingAddress().strip()))) options.add(row);
            if (options.size() == SUGGESTION_LIMIT) break;
        }
        return new Suggestions(List.copyOf(options), options.size() > 1);
    }

    /** Field names only: never echo another order's personal data into model text or logs. */
    public List<String> conflicts(Long ownerId, Map<String, Object> current) {
        if (current == null || current.isEmpty()) return List.of();
        Suggestions history;
        try {
            history = suggestions(ownerId);
        } catch (DataAccessException unavailable) {
            return List.of();
        }
        if (history.options().isEmpty()) return List.of();
        Map<String, String> present = new java.util.LinkedHashMap<>();
        for (String field : List.of("recipientName", "recipientPhone", "shippingAddress")) {
            String value = currentValue(current, field);
            if (value != null) present.put(field, value);
        }
        if (present.isEmpty() || history.options().stream().anyMatch(option ->
                present.entrySet().stream().allMatch(entry ->
                        entry.getValue().equals(valueOf(option, entry.getKey()))))) return List.of();
        List<String> conflicts = new ArrayList<>();
        for (var entry : present.entrySet()) {
            if (history.options().stream().noneMatch(option ->
                    entry.getValue().equals(valueOf(option, entry.getKey())))) conflicts.add(entry.getKey());
        }
        // Each field may match a different old order while the combined address is new.
        return List.copyOf(conflicts.isEmpty() ? present.keySet() : conflicts);
    }

    private static String currentValue(Map<String, Object> current, String field) {
        for (String key : OrderClarificationSchema.defaultSchema().aliases(field)) {
            Object raw = current.get(key);
            if (raw instanceof String value && !value.isBlank()) return value.strip();
        }
        return null;
    }

    private static String valueOf(Suggestion option, String field) {
        return switch (field) {
            case "recipientName" -> option.recipientName().strip();
            case "recipientPhone" -> option.recipientPhone().strip();
            case "shippingAddress" -> option.shippingAddress().strip();
            default -> "";
        };
    }

    private static boolean usable(Suggestion option) {
        return option != null && option.orderDate() != null
                && option.recipientName() != null && !option.recipientName().isBlank()
                && option.recipientName().length() <= 40
                && option.recipientName().chars().noneMatch(Character::isISOControl)
                && option.recipientPhone() != null && option.recipientPhone().matches("1[3-9][0-9]{9}")
                && OrderShippingAddressPolicy.usable(option.shippingAddress());
    }
}

