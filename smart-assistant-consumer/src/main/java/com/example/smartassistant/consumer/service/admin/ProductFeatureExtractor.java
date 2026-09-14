package com.example.smartassistant.consumer.service.admin;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded, evidence-only intake parser. Description text is data, never instructions. */
@Component
public class ProductFeatureExtractor {
    public static final String VERSION = "intake-rules-v1";
    private static final Pattern WEIGHT = Pattern.compile(
            "(?:整机净重|机身净重|设备净重|净重|机身重量|整机重量|重量)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)\\s*(kg|公斤|千克|克|g)(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUNTIME = Pattern.compile(
            "(?:续航(?:时间|时长)?|播放(?:时间|时长)?|听歌(?:时间|时长)?)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:小时|h)(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNCERTAIN = Pattern.compile("约|左右|大概|大约|最长|最高|最多|至少|不超过|小于|低于|大于|高于|以内|以下|以上|[<>≤≥~～]|\\d\\s*[-至到]\\s*\\d");
    private static final Pattern WEIGHT_OTHER = Pattern.compile("包装|毛重|充电盒|单耳|每只|每侧|含盒|带盒|含配件|含键盘");
    private static final Pattern BATTERY_OTHER = Pattern.compile("充电盒|配合充电|总续航|充电时间|待机|包装");
    private static final Pattern ANC_YES = Pattern.compile("(?:支持|具备|配备|搭载|提供)(?:ANC)?主动降噪|支持ANC(?:主动)?降噪", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANC_NO = Pattern.compile("(?:不支持|不具备|未配备|没有|无)(?:ANC)?主动降噪|不支持ANC(?:主动)?降噪", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ASSERTION = Pattern.compile("[?？]|是否|不需要|不要求|无需|不要|并非|不是不|可能|据说|如果|假如|假设|忽略|指令");

    public record Features(BigDecimal weightGrams, BigDecimal batteryLifeHours,
                           String batteryLifeScenario, Boolean noiseCancelling) {
        public boolean known() { return weightGrams != null || batteryLifeHours != null || noiseCancelling != null; }
    }
    public record Extraction(String version, Features features, Map<String, String> evidence, List<String> warnings) { }
    private record Battery(BigDecimal hours, String scenario) { }

    public Extraction extract(String description, String spec) {
        if (description == null || spec == null || description.length() > 10000 || spec.length() > 10000)
            throw new IllegalArgumentException("简介和规格各最多 10000 字");
        Map<BigDecimal, String> weights = new LinkedHashMap<>();
        Map<Battery, String> batteries = new LinkedHashMap<>();
        Map<Boolean, String> anc = new LinkedHashMap<>();
        Set<String> warnings = new LinkedHashSet<>();
        boolean weightUncertain = false, batteryUncertain = false;
        for (var entry : Map.of("简介", description, "规格", spec).entrySet()) {
            for (String raw : entry.getValue().split("[，,。；;\\r\\n]+")) {
                String clause = raw.trim();
                if (clause.isEmpty()) continue;
                String evidence = entry.getKey() + "：" + clause.substring(0, Math.min(240, clause.length()));
                if (NON_ASSERTION.matcher(clause).find()) {
                    warnings.add("疑问、假设或指令性表述不作为商品参数依据。");
                    continue;
                }
                var weight = WEIGHT.matcher(clause);
                while (weight.find()) {
                    if (WEIGHT_OTHER.matcher(clause).find()) {
                        warnings.add("包装、配件或单耳重量不能直接当作设备净重。");
                        continue;
                    }
                    if (UNCERTAIN.matcher(clause).find()) { weightUncertain = true; continue; }
                    BigDecimal value = new BigDecimal(weight.group(1));
                    if (Set.of("kg", "公斤", "千克").contains(weight.group(2).toLowerCase(Locale.ROOT)))
                        value = value.multiply(BigDecimal.valueOf(1000));
                    value = value.stripTrailingZeros();
                    if (valid(value, 3, "9999999.999")) weights.put(value, evidence);
                    else { weightUncertain = true; warnings.add("重量超出允许范围或精度，请核对原文。"); }
                }
                var runtime = RUNTIME.matcher(clause);
                while (runtime.find()) {
                    if (BATTERY_OTHER.matcher(clause).find()) {
                        warnings.add("充电盒总续航、待机或充电时长不作为设备使用续航。");
                        continue;
                    }
                    String scenario = scenario(clause);
                    BigDecimal hours = new BigDecimal(runtime.group(1)).stripTrailingZeros();
                    if (scenario == null || UNCERTAIN.matcher(clause).find() || !valid(hours, 2, "999999.99")) {
                        batteryUncertain = true;
                        continue;
                    }
                    batteries.put(new Battery(hours, scenario), evidence);
                }
                if (ANC_NO.matcher(clause).find()) {
                    anc.put(false, evidence);
                    // Remove a negative claim before detecting a conflicting positive in the same clause.
                    if (ANC_YES.matcher(ANC_NO.matcher(clause).replaceAll("")).find()) anc.put(true, evidence);
                } else if (ANC_YES.matcher(clause).find()) anc.put(true, evidence);
            }
        }
        Map<String, String> evidence = new LinkedHashMap<>();
        BigDecimal weight = null;
        if (weights.size() == 1 && !weightUncertain) {
            weight = weights.keySet().iterator().next(); evidence.put("weightGrams", weights.get(weight));
        } else if (weights.size() > 1 || weightUncertain) warnings.add("净重存在冲突或近似/上下限表述，留空等待人工核对。");
        Battery battery = null;
        if (batteries.size() == 1 && !batteryUncertain) {
            battery = batteries.keySet().iterator().next();
            evidence.put("batteryLifeHours", batteries.get(battery));
            evidence.put("batteryLifeScenario", batteries.get(battery));
        } else if (batteries.size() > 1 || batteryUncertain) warnings.add("续航缺少明确场景、存在近似值或多种测试口径，请人工选择并核对。");
        Boolean noise = null;
        if (anc.size() == 1) { noise = anc.keySet().iterator().next(); evidence.put("noiseCancelling", anc.get(noise)); }
        else if (anc.size() > 1) warnings.add("主动降噪的支持情况存在冲突，请人工核对。");
        Features features = new Features(weight, battery == null ? null : battery.hours,
                battery == null ? null : battery.scenario, noise);
        if (!features.known()) warnings.add("没有提取到明确参数；轻便、长续航、容量等描述不会被推测成重量或使用时长。");
        return new Extraction(VERSION, features, Collections.unmodifiableMap(evidence), List.copyOf(warnings));
    }

    private static boolean valid(BigDecimal value, int scale, String max) {
        return value.signum() > 0 && value.scale() <= scale && value.compareTo(new BigDecimal(max)) <= 0;
    }

    private static String scenario(String text) {
        Set<String> scenarios = new HashSet<>();
        if (text.contains("视频播放") || text.contains("播放视频") || text.contains("视频续航")) scenarios.add("video_playback");
        boolean audio = text.contains("听歌") || text.contains("音乐播放") || text.contains("音频播放");
        if (audio && Pattern.compile("(?:开启|打开|启用)(?:主动)?降噪").matcher(text).find()) scenarios.add("audio_anc_on");
        if (audio && Pattern.compile("(?:关闭|关掉|停用)(?:主动)?降噪").matcher(text).find()) scenarios.add("audio_anc_off");
        if (text.contains("综合使用") || text.contains("混合使用") || text.contains("综合续航")) scenarios.add("mixed_use");
        return scenarios.size() == 1 ? scenarios.iterator().next() : null;
    }
}
