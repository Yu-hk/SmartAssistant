package com.example.smartassistant.intake.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** A small, explicit identity vocabulary; free-form user queries never write it. */
public record ProductIdentityUpdate(long expectedRevision, Map<String, String> metadata) {
    public static ProductIdentityUpdate parse(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("expectedRevision").isIntegralNumber()
                || !body.path("expectedRevision").canConvertToLong() || body.path("expectedRevision").longValue() < 0
                || body.path("expectedRevision").longValue() == Long.MAX_VALUE)
            throw invalid("请提供有效的 expectedRevision");
        Set<String> allowed = Set.of("expectedRevision", "brand", "family", "model", "generation", "variant", "parentCode", "source");
        body.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid("存在未知身份字段"); });
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : List.of("brand", "family", "model", "generation", "variant", "parentCode", "source")) {
            JsonNode node = body.get(key);
            if (node != null && !node.isTextual()) throw invalid("身份字段必须是文字");
            String value = node == null ? "" : node.asText().trim();
            if (value.length() > (key.equals("source") ? 200 : 80) || value.chars().anyMatch(Character::isISOControl))
                throw invalid("身份字段过长或包含控制字符");
            if (key.equals("parentCode") && !value.isEmpty() && !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}"))
                throw invalid("父商品编码无效");
            metadata.put(key, key.equals("parentCode") ? value.toUpperCase(Locale.ROOT) : value);
        }
        if (metadata.get("source").isBlank()) throw invalid("请注明身份信息的核实来源");
        return new ProductIdentityUpdate(body.path("expectedRevision").longValue(), Collections.unmodifiableMap(metadata));
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
