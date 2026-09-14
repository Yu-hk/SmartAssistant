package com.example.smartassistant.service.quality;

import java.util.LinkedHashSet;
import java.util.Set;

/** Synchronous Product tool evidence, isolated to one invocation and never retained after close. */
public final class ProductToolEvidenceScope implements AutoCloseable {
    private static final int MAX_CHARS = 12_000;
    private static final ThreadLocal<ProductToolEvidenceScope> CURRENT = new ThreadLocal<>();
    private final ProductToolEvidenceScope previous;
    private final Set<String> observations = new LinkedHashSet<>();
    private int size;

    private ProductToolEvidenceScope() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static ProductToolEvidenceScope open() { return new ProductToolEvidenceScope(); }

    /** Only successful plain-text catalog observations, never errors or user/model text. */
    public static void record(String result) {
        ProductToolEvidenceScope scope = CURRENT.get();
        if (scope == null || result == null || result.isBlank()) return;
        String text = result.trim();
        if (text.startsWith("{") || text.contains("未找到") || text.contains("不可用")
                || text.contains("查询失败") || text.contains("error_code")) return;
        // Drop a too-large observation in full; truncated evidence could change a factual claim.
        if (scope.size + text.length() > MAX_CHARS || !scope.observations.add(text)) return;
        scope.size += text.length();
    }

    public boolean hasEvidence() { return !observations.isEmpty(); }

    public String combine(String retrievedContext) {
        String retrieval = retrievedContext == null ? "" : retrievedContext;
        return hasEvidence() ? retrieval + "\n\n[本次商品查询的实际返回数据]\n"
                + String.join("\n\n", observations) : retrieval;
    }

    @Override public void close() {
        observations.clear();
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
