package com.example.smartassistant.routing.contract;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Canonical workflow operation registry shared by Router and domain agents. */
public enum WorkflowOperation {
    DISCOVER_PRODUCTS(Domain.PRODUCT, Access.READ, false),
    QUERY_HOT_PRODUCTS(Domain.PRODUCT, Access.READ, false),
    QUERY_PRODUCT(Domain.PRODUCT, Access.READ, false),
    ANALYZE_PRODUCT_DATA(Domain.PRODUCT, Access.READ, false),
    RECOMMEND_PRODUCT(Domain.PRODUCT, Access.READ, false),
    QUERY_ORDER(Domain.ORDER, Access.READ, false),
    QUERY_ORDER_LIST(Domain.ORDER, Access.READ, false),
    QUERY_PAYMENT_PENDING(Domain.ORDER, Access.READ, false),
    CREATE_ORDER(Domain.ORDER, Access.WRITE, false),
    CANCEL_ORDER(Domain.ORDER, Access.WRITE, true),
    REFUND_ORDER(Domain.ORDER, Access.WRITE, true),
    PAY_ORDER(Domain.ORDER, Access.WRITE, true),
    SHIP_ORDER(Domain.ORDER, Access.WRITE, true),
    TRACK_LOGISTICS(Domain.ORDER, Access.READ, false),
    CONFIRM_DELIVERY(Domain.ORDER, Access.WRITE, true),
    EXPLAIN_ORDER_REQUIREMENTS(Domain.ORDER, Access.READ, false),
    EXPLAIN_ORDER_LIFECYCLE(Domain.ORDER, Access.READ, false),
    ANSWER(Domain.GENERAL, Access.READ, false);

    private final Domain domain;
    private final Access access;
    private final boolean approvalRequired;

    WorkflowOperation(Domain domain, Access access, boolean approvalRequired) {
        this.domain = domain;
        this.access = access;
        this.approvalRequired = approvalRequired;
    }

    public String code() {
        return name();
    }

    public Domain domain() {
        return domain;
    }

    public Access access() {
        return access;
    }

    public boolean isWrite() {
        return access == Access.WRITE;
    }

    public boolean approvalRequired() {
        return approvalRequired;
    }

    public static Optional<WorkflowOperation> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(WorkflowOperation::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static String promptCodes() {
        return String.join("、", codes());
    }

    public enum Domain { PRODUCT, ORDER, GENERAL }
    public enum Access { READ, WRITE }
}
