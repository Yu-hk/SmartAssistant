package com.example.smartassistant.consumer.service.infrastructure;

import java.util.regex.Pattern;

/** Produces a bounded prompt snapshot suitable for privileged diagnostics. */
public final class PromptAuditSanitizer {

    private static final int MAX_LENGTH = 2_000;
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+\\-/]+=*");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?\\b(?:password|passwd|api[-_]?key|secret|access[-_]?token|refresh[-_]?token)" +
                    "[\"']?\\s*[:=]\\s*)([\"']?)([^\"'\\s,;}]+)([\"']?)");

    private PromptAuditSanitizer() {
    }

    public static String sanitize(String prompt) {
        if (prompt == null) return null;
        String sanitized = BEARER.matcher(prompt).replaceAll("Bearer [REDACTED]");
        sanitized = JWT.matcher(sanitized).replaceAll("[REDACTED_JWT]");
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1$2[REDACTED]$4");
        sanitized = sanitized.strip();
        if (sanitized.isEmpty()) return null;
        return sanitized.length() <= MAX_LENGTH
                ? sanitized : sanitized.substring(0, MAX_LENGTH) + "…";
    }
}
