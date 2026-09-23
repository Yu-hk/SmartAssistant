package com.example.smartassistant.router.service.core;

import org.springframework.stereotype.Component;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;
import java.io.StringReader;
import java.util.*;

/** Coarse fallback dispatch only. Field extraction, validation and replies belong to the Agent. */
@Component
public class BusinessFallbackParser {
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
        if (tokens.stream().anyMatch(t -> Set.of("下单", "购买", "取消", "退款", "退单", "退货").contains(t))
                || q.matches("(?s).*(下单|购买|取消订单|退款|退单|退货).*"))
            return new Parsed(Kind.ORDER, q);
        if (tokens.stream().anyMatch(t -> Set.of("价格", "多少钱", "规格", "颜色", "库存", "有货", "货").contains(t))
                || q.matches(".*(多少钱|有货吗|规格|颜色).*"))
            return new Parsed(Kind.PRODUCT_QUERY, q);
        return new Parsed(Kind.UNKNOWN, q);
    }
}
