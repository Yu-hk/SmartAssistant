package com.example.smartassistant.service.core;

import java.util.regex.Pattern;

/** Presentation-only cleanup; catalog records, tool evidence and audit inputs stay unchanged. */
public final class ProductPublicAnswer {
    private static final String LABEL = "(?:商品(?:编码|编号|代码)|内部商品编码|SKU|product[_ ]?code)";
    private static final String VALUE = "[A-Za-z0-9][A-Za-z0-9_.:/-]*";
    private static final String FIELD = "(?i)(?:\\*{1,2})?" + LABEL
            + "(?:\\*{1,2})?[ \\t]*[:：]?[ \\t]*(?:\\*{1,2}|`)?" + VALUE
            + "(?:\\*{1,2}|`)?";
    private static final Pattern PARENTHETICAL = Pattern.compile("[（(][ \\t]*" + FIELD + "[ \\t]*[）)]");
    private static final Pattern STANDALONE = Pattern.compile("(?m)^[ \\t]*(?:[-*][ \\t]+)?" + FIELD + "[。；;]?[ \\t]*$\\R?");
    private static final Pattern INLINE = Pattern.compile(FIELD + "[ \\t]*[，,；;]?[ \\t]*");

    private ProductPublicAnswer() { }

    public static String format(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        String result = PARENTHETICAL.matcher(answer).replaceAll("");
        result = STANDALONE.matcher(result).replaceAll("");
        result = INLINE.matcher(result).replaceAll("");
        return result.replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
