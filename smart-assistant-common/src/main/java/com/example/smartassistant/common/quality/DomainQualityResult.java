/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.quality;

import java.util.Arrays;
import java.util.List;

/**
 * A deterministic quality decision made by a domain Agent.
 *
 * <p>The reason codes are deliberately ASCII-only so the decision can safely
 * travel in HTTP headers while the response body remains backward-compatible
 * plain text.</p>
 */
public final class DomainQualityResult {

    public enum Status {
        PASS,
        WARN,
        FAIL,
        UNKNOWN
    }

    private static final DomainQualityResult UNKNOWN =
            new DomainQualityResult(Status.UNKNOWN, 0.5, List.of("NOT_EVALUATED"));

    private final Status status;
    private final double score;
    private final List<String> reasonCodes;

    public DomainQualityResult(Status status, double score, List<String> reasonCodes) {
        this.status = status != null ? status : Status.UNKNOWN;
        this.score = Math.max(0.0, Math.min(1.0, score));
        this.reasonCodes = reasonCodes == null
                ? List.of()
                : reasonCodes.stream()
                        .filter(code -> code != null && !code.isBlank())
                        .map(DomainQualityResult::sanitizeReasonCode)
                        .distinct()
                        .toList();
    }

    public static DomainQualityResult pass(double score, String... reasonCodes) {
        return new DomainQualityResult(Status.PASS, score, list(reasonCodes));
    }

    public static DomainQualityResult warn(double score, String... reasonCodes) {
        return new DomainQualityResult(Status.WARN, score, list(reasonCodes));
    }

    public static DomainQualityResult fail(String... reasonCodes) {
        return new DomainQualityResult(Status.FAIL, 0.0, list(reasonCodes));
    }

    public static DomainQualityResult unknown() {
        return UNKNOWN;
    }

    public static DomainQualityResult fromHeaders(String statusValue, String scoreValue,
                                                   String reasonCodesValue) {
        if (statusValue == null || statusValue.isBlank()) {
            return unknown();
        }
        try {
            Status parsedStatus = Status.valueOf(statusValue.trim().toUpperCase());
            double parsedScore = scoreValue == null || scoreValue.isBlank()
                    ? defaultScore(parsedStatus)
                    : Double.parseDouble(scoreValue);
            List<String> parsedReasons = reasonCodesValue == null || reasonCodesValue.isBlank()
                    ? List.of()
                    : Arrays.stream(reasonCodesValue.split(","))
                            .map(String::trim)
                            .filter(value -> !value.isBlank())
                            .toList();
            return new DomainQualityResult(parsedStatus, parsedScore, parsedReasons);
        } catch (IllegalArgumentException ex) {
            return unknown();
        }
    }

    /** Returns the more severe of two domain decisions. */
    public DomainQualityResult worst(DomainQualityResult other) {
        if (other == null) return this;
        if (severity(other.status) > severity(status)) return other;
        if (severity(other.status) < severity(status)) return this;
        return other.score < score ? other : this;
    }

    public String reasonCodesHeaderValue() {
        return String.join(",", reasonCodes);
    }

    public boolean isPass() { return status == Status.PASS; }
    public boolean isWarn() { return status == Status.WARN; }
    public boolean isFail() { return status == Status.FAIL; }
    public boolean isUnknown() { return status == Status.UNKNOWN; }

    public Status getStatus() { return status; }
    public double getScore() { return score; }
    public List<String> getReasonCodes() { return reasonCodes; }

    private static List<String> list(String... values) {
        return values == null ? List.of() : Arrays.asList(values);
    }

    private static double defaultScore(Status status) {
        return switch (status) {
            case PASS -> 1.0;
            case WARN, UNKNOWN -> 0.5;
            case FAIL -> 0.0;
        };
    }

    private static int severity(Status status) {
        return switch (status) {
            case PASS -> 1;
            case UNKNOWN -> 2;
            case WARN -> 3;
            case FAIL -> 4;
        };
    }

    private static String sanitizeReasonCode(String value) {
        return value.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "_");
    }
}
