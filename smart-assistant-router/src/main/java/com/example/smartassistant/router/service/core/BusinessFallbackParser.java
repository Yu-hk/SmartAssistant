package com.example.smartassistant.router.service.core;

import org.springframework.stereotype.Component;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;
import java.io.StringReader;
import java.util.*;

/** Coarse fallback dispatch only. Field extraction, validation and replies belong to the Agent. */
@Component
public class BusinessFallbackParser {
    private final FallbackDispatchSchema schema;
    public BusinessFallbackParser() { this(FallbackDispatchSchema.defaultSchema()); }
    BusinessFallbackParser(FallbackDispatchSchema schema) { this.schema = schema; }
    public enum Kind { PRODUCT_QUERY, ORDER, UNKNOWN }
    public record Parsed(Kind kind, String question) { }
    public Parsed parse(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty() || q.length() > 500) return new Parsed(Kind.UNKNOWN, q);
        Set<String> tokens = new HashSet<>();
        try {
            IKSegmenter segmenter = new IKSegmenter(new StringReader(q), true);
            for (Lexeme token; (token = segmenter.next()) != null;) tokens.add(token.getLexemeText());
        } catch (Exception failure) { return new Parsed(Kind.UNKNOWN, q); }
        if (tokens.isEmpty()) return new Parsed(Kind.UNKNOWN, q);
        if (schema.matches("order", tokens, q))
            return new Parsed(Kind.ORDER, q);
        if (schema.matches("product", tokens, q))
            return new Parsed(Kind.PRODUCT_QUERY, q);
        return new Parsed(Kind.UNKNOWN, q);
    }
}
