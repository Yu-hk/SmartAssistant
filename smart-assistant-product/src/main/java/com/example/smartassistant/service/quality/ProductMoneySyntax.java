package com.example.smartassistant.service.quality;

import java.math.BigDecimal;

/** Financial notation normalization for validation copies, never for displayed model output. */
public final class ProductMoneySyntax {
    static final String DECIMAL = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?";
    static final String END = "(?![\\d.]|,\\d|%)";
    static final String START = "(?<![A-Za-z\\d.+\\-$€£])(?<!\\d,)";

    private ProductMoneySyntax() { }

    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '０' && c <= '９') result.append((char) ('0' + c - '０'));
            // Preserve prose commas; only translate commas inside full-width numeric text.
            else if (c == '，' && i > 0 && i + 1 < text.length()
                    && text.charAt(i - 1) >= '０' && text.charAt(i - 1) <= '９'
                    && text.charAt(i + 1) >= '０' && text.charAt(i + 1) <= '９') result.append(',');
            else result.append(switch (c) {
                case '＜' -> '<';
                case '＞' -> '>';
                case '＝' -> '=';
                case '．' -> '.';
                case '％' -> '%';
                case '－', '−' -> '-';
                case '\u00a0', '\u3000' -> ' ';
                default -> c;
            });
        }
        return result.toString();
    }

    static BigDecimal number(String raw) {
        return new BigDecimal(raw.replace(",", "")).stripTrailingZeros();
    }
}
