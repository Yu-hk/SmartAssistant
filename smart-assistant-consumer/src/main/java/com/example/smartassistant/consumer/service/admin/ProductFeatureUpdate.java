package com.example.smartassistant.consumer.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

/** Full replacement contract: explicit null clears a field; omitted/unknown fields are rejected. */
public record ProductFeatureUpdate(long expectedRevision, BigDecimal weightGrams, BigDecimal batteryLifeHours,
                                   String batteryLifeScenario, Boolean noiseCancelling,
                                   String source, OffsetDateTime verifiedAt) {
    private static final Set<String> FIELDS = Set.of("weightGrams", "batteryLifeHours", "batteryLifeScenario",
            "noiseCancelling", "source", "verifiedAt");
    private static final Set<String> SCENARIOS = Set.of("video_playback", "audio_anc_on", "audio_anc_off", "mixed_use");

    public static ProductFeatureUpdate parse(JsonNode body, Clock clock) {
        exactFields(body, Set.of("expectedRevision", "features"));
        JsonNode revision = body.get("expectedRevision");
        if (!revision.isIntegralNumber() || !revision.canConvertToLong()
                || revision.longValue() < 0 || revision.longValue() == Long.MAX_VALUE) {
            throw invalid("expectedRevision 必须是非负整数，请先读取当前版本");
        }
        JsonNode fields = body.get("features");
        exactFields(fields, FIELDS);
        BigDecimal weight = decimal(fields.get("weightGrams"), "weightGrams", 3, "9999999.999");
        BigDecimal battery = decimal(fields.get("batteryLifeHours"), "batteryLifeHours", 2, "999999.99");
        String scenario = text(fields.get("batteryLifeScenario"), "batteryLifeScenario", 32);
        JsonNode ancValue = fields.get("noiseCancelling");
        if (!ancValue.isNull() && !ancValue.isBoolean()) throw invalid("noiseCancelling 只能是 true、false 或 null");
        Boolean anc = ancValue.isNull() ? null : ancValue.booleanValue();
        String source = text(fields.get("source"), "source", 2000);
        String verified = text(fields.get("verifiedAt"), "verifiedAt", 64);
        OffsetDateTime verifiedAt = null;
        if (verified != null) {
            try { verifiedAt = OffsetDateTime.parse(verified).withOffsetSameInstant(ZoneOffset.UTC); }
            catch (java.time.DateTimeException e) { throw invalid("verifiedAt 必须是带时区的 ISO-8601 时间"); }
            if (verifiedAt.toInstant().isAfter(clock.instant())) throw invalid("核验时间不能晚于当前时间");
        }
        if ((battery == null) != (scenario == null) || scenario != null && !SCENARIOS.contains(scenario)) {
            throw invalid("续航时长与受支持的测试场景必须同时提供或同时设为 null");
        }
        boolean known = weight != null || battery != null || anc != null;
        if (known && (source == null || verifiedAt == null)) throw invalid("保存已知参数必须提供数据来源和核验时间");
        if (!known && (source != null || verifiedAt != null)) throw invalid("全部参数未知时请同时清空来源和核验时间");
        return new ProductFeatureUpdate(revision.longValue(), weight, battery, scenario, anc, source, verifiedAt);
    }

    private static void exactFields(JsonNode object, Set<String> required) {
        if (object == null || !object.isObject()) throw invalid("请求和 features 必须是 JSON 对象");
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(required)) throw invalid("请完整提供约定字段；未知值显式填写 null，不允许未知字段");
    }

    private static BigDecimal decimal(JsonNode value, String name, int scale, String max) {
        if (value.isNull()) return null;
        if (!value.isNumber()) throw invalid(name + " 必须是数字或 null");
        BigDecimal number = value.decimalValue().stripTrailingZeros();
        if (number.signum() <= 0 || number.scale() > scale || number.compareTo(new BigDecimal(max)) > 0) {
            throw invalid(name + " 超出允许范围或精度");
        }
        return number;
    }

    private static String text(JsonNode value, String name, int maxLength) {
        if (value.isNull()) return null;
        if (!value.isTextual()) throw invalid(name + " 必须是文本或 null");
        String text = value.textValue().trim();
        if (text.length() > maxLength || text.chars().anyMatch(Character::isISOControl)) throw invalid(name + " 长度或格式不合法");
        return text.isEmpty() ? null : text;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
