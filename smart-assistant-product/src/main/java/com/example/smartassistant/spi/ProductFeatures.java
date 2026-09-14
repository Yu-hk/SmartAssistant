package com.example.smartassistant.spi;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Normalized catalog facts, never inferred from marketing/spec prose. Null means unknown. */
public record ProductFeatures(BigDecimal weightGrams, BigDecimal batteryLifeHours,
                              String batteryLifeScenario, Boolean noiseCancelling,
                              String source, String verifiedAt) {
    public static final Set<String> BATTERY_SCENARIOS = Set.of(
            "video_playback", "audio_anc_on", "audio_anc_off", "mixed_use");
    public static final ProductFeatures UNKNOWN = new ProductFeatures(null, null, "", null, "", "");

    public ProductFeatures {
        weightGrams = positive(weightGrams);
        batteryLifeHours = positive(batteryLifeHours);
        batteryLifeScenario = text(batteryLifeScenario);
        source = text(source);
        verifiedAt = text(verifiedAt);
    }

    public boolean documented() { return !source.isBlank() && !verifiedAt.isBlank(); }

    public static ProductFeatures from(Object value) {
        if (value instanceof ProductFeatures features) return features;
        if (!(value instanceof Map<?, ?> map)) return UNKNOWN;
        return new ProductFeatures(decimal(map.get("weightGrams")), decimal(map.get("batteryLifeHours")),
                text(map.get("batteryLifeScenario")), map.get("noiseCancelling") instanceof Boolean bool ? bool : null,
                text(map.get("source")), text(map.get("verifiedAt")));
    }

    public String evidence() {
        if (!documented()) return "结构化特征尚无完整来源和核验记录";
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (weightGrams != null) parts.add("设备净重" + number(weightGrams) + "克");
        if (batteryLifeHours != null && BATTERY_SCENARIOS.contains(batteryLifeScenario)) {
            parts.add("标称续航" + number(batteryLifeHours) + "小时（" + scenarioLabel(batteryLifeScenario) + "）");
        }
        if (noiseCancelling != null) parts.add(noiseCancelling ? "支持主动降噪" : "不支持主动降噪");
        if (parts.isEmpty()) return "结构化特征未提供";
        return String.join("；", parts) + "。数据来源：" + source + "；核验时间：" + verifiedAt;
    }

    public static String scenarioLabel(String value) {
        return switch (value) {
            case "video_playback" -> "视频播放";
            case "audio_anc_on" -> "开启主动降噪听歌";
            case "audio_anc_off" -> "关闭主动降噪听歌";
            case "mixed_use" -> "综合使用";
            default -> "场景未知";
        };
    }

    private static BigDecimal decimal(Object value) {
        try { return value == null ? null : positive(new BigDecimal(value.toString())); }
        catch (NumberFormatException e) { return null; }
    }
    private static BigDecimal positive(BigDecimal value) { return value != null && value.signum() > 0 ? value : null; }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
