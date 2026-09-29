package com.example.smartassistant.intake.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Full replacement of administrator-declared suitability; unknown remains an empty list. */
public record ProductSuitabilityUpdate(long expectedRevision, List<String> audiences,
                                       List<String> useCases, String source) {
    private static final Set<String> BODY = Set.of("expectedRevision", "suitability");
    private static final Set<String> FIELDS = Set.of("audiences", "useCases", "source", "confirmed");

    public static ProductSuitabilityUpdate parse(JsonNode body) {
        exactFields(body, BODY);
        JsonNode revision = body.get("expectedRevision");
        if (!revision.isIntegralNumber() || !revision.canConvertToLong()
                || revision.longValue() < 0 || revision.longValue() == Long.MAX_VALUE) {
            throw invalid("expectedRevision 必须是非负整数，请先读取当前版本");
        }
        JsonNode suitability = body.get("suitability");
        exactFields(suitability, FIELDS);
        List<String> audiences = tags(suitability.get("audiences"), "audiences");
        List<String> useCases = tags(suitability.get("useCases"), "useCases");
        JsonNode sourceNode = suitability.get("source");
        if (!sourceNode.isTextual()) throw invalid("source 必须是文本");
        String source = sourceNode.textValue().trim();
        if (source.length() > 500 || source.chars().anyMatch(Character::isISOControl)) {
            throw invalid("标注依据长度或格式不正确");
        }
        JsonNode confirmed = suitability.get("confirmed");
        if (!confirmed.isBoolean()) throw invalid("confirmed 必须是布尔值");
        boolean hasTags = !audiences.isEmpty() || !useCases.isEmpty();
        if (hasTags && (!confirmed.booleanValue() || source.isBlank())) {
            throw invalid("适用标签必须提供标注依据并由管理员确认");
        }
        if (!hasTags && !source.isBlank()) throw invalid("没有适用标签时请清空标注依据");
        return new ProductSuitabilityUpdate(revision.longValue(), audiences, useCases,
                hasTags ? source : null);
    }

    private static List<String> tags(JsonNode values, String name) {
        if (!values.isArray() || values.size() > 12) throw invalid(name + " 最多填写 12 个标签");
        List<String> tags = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw invalid(name + " 只能包含文本标签");
            String tag = value.textValue().trim();
            // A tag is a compact catalog category, never an instruction or free-form claim.
            if (!tag.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}·_-]{0,39}")
                    || !seen.add(tag.toLowerCase(Locale.ROOT))) {
                throw invalid(name + " 包含无效或重复标签");
            }
            tags.add(tag);
        }
        return List.copyOf(tags);
    }

    private static void exactFields(JsonNode object, Set<String> required) {
        if (object == null || !object.isObject()) throw invalid("请求和 suitability 必须是 JSON 对象");
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(required)) throw invalid("请完整提供适用标签字段，不允许未知字段");
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
