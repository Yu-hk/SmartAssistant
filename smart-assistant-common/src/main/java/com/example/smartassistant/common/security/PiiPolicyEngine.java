package com.example.smartassistant.common.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Single PII policy used at model, tool, streaming and observability boundaries.
 * Replacements intentionally retain only a semantic tag, never the source value.
 */
public final class PiiPolicyEngine {

    private static final PiiPolicyEngine SHARED = new PiiPolicyEngine();

    private final List<Rule> rules = List.of(
            new Rule(Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])"), "[PHONE]"),
            new Rule(Pattern.compile("(?<![0-9])\\d{17}[\\dXx](?![0-9])|(?<![0-9])\\d{15}(?![0-9])"), "[ID_CARD]"),
            new Rule(Pattern.compile("(?<![0-9])\\d{16,19}(?![0-9])"), "[BANK_CARD]"),
            new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]"),
            new Rule(Pattern.compile("(?<![0-9])(?:10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})(?![0-9])"), "[INTERNAL_IP]"),
            new Rule(Pattern.compile("(工号|员工编号|employee[_\\s]?id)[:：]?\\s*([A-Za-z0-9]{4,})", Pattern.CASE_INSENSITIVE), "$1[EMP_ID]")
    );
    private static final Pattern MASK_PHONE = Pattern.compile("(?<!\\d)(\\d{3})\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern MASK_ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{4})(?!\\d)");
    private static final Pattern MASK_BANK_CARD = Pattern.compile("(?<!\\d)(\\d{4})\\d{8,11}(\\d{4})(?!\\d)");

    public static PiiPolicyEngine shared() { return SHARED; }

    public String sanitize(String text) {
        if (text == null || text.isBlank()) return text;
        String sanitized = text;
        for (Rule rule : rules) sanitized = rule.pattern().matcher(sanitized).replaceAll(rule.replacement());
        return sanitized;
    }

    public boolean containsPii(String text) {
        if (text == null || text.isBlank()) return false;
        return rules.stream().anyMatch(rule -> rule.pattern().matcher(text).find());
    }

    /** User-facing masking policy that preserves only a small recognition prefix/suffix. */
    public String mask(String text) {
        if (text == null || text.isBlank()) return text;
        String masked = MASK_PHONE.matcher(text).replaceAll("$1****$2");
        masked = MASK_ID_CARD.matcher(masked).replaceAll("$1********$2");
        masked = MASK_BANK_CARD.matcher(masked).replaceAll("$1****$2");
        masked = rules.get(3).pattern().matcher(masked).replaceAll("[EMAIL]");
        masked = rules.get(4).pattern().matcher(masked).replaceAll("[INTERNAL_IP]");
        masked = rules.get(5).pattern().matcher(masked).replaceAll("$1[EMP_ID]");
        return masked;
    }

    private record Rule(Pattern pattern, String replacement) {}
}
