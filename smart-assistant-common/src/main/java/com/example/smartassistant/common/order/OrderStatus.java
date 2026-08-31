package com.example.smartassistant.common.order;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical order lifecycle shared by order tools, validators and read models.
 * Database values stay compatible with the existing Chinese status column while
 * transition rules have a single source of truth.
 */
public enum OrderStatus {
    PENDING_PAYMENT("待付款", Set.of("待支付")),
    PENDING_SHIPMENT("待发货", Set.of("已付款", "已支付")),
    SHIPPED("已发货", Set.of()),
    DELIVERED("已签收", Set.of("已完成")),
    CANCELLED("已取消", Set.of()),
    REFUNDING("退款中", Set.of());

    private final String value;
    private final Set<String> aliases;

    OrderStatus(String value, Set<String> aliases) {
        this.value = value;
        this.aliases = aliases;
    }

    public String value() {
        return value;
    }

    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        String normalized = candidate.trim();
        return value.equals(normalized) || aliases.contains(normalized) || name().equalsIgnoreCase(normalized);
    }

    public boolean isMentionedIn(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains(value) || aliases.stream().anyMatch(text::contains);
    }

    public boolean canTransitionTo(OrderStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case PENDING_PAYMENT -> target == PENDING_SHIPMENT || target == CANCELLED;
            case PENDING_SHIPMENT -> target == SHIPPED || target == CANCELLED;
            case SHIPPED, DELIVERED -> target == REFUNDING || (this == SHIPPED && target == DELIVERED);
            case CANCELLED, REFUNDING -> false;
        };
    }

    public static Optional<OrderStatus> from(String candidate) {
        return Arrays.stream(values()).filter(status -> status.matches(candidate)).findFirst();
    }

    public static Optional<OrderStatus> firstMentionedIn(String text) {
        return Arrays.stream(values()).filter(status -> status.isMentionedIn(text)).findFirst();
    }

    public static List<String> valuesForStorage() {
        return Arrays.stream(values()).map(OrderStatus::value).toList();
    }
}
