/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实体归一化服务——将自然语言表达转换为系统可用标准值。
 * <p>
 * 对应评测维度：实体归一化、输入鲁棒性。
 * </p>
 * <p>
 * 覆盖类型：
 * <ul>
 *   <li>日期归一化：明天/后天/下周一/2026年4月 → 标准日期</li>
 *   <li>地点别名：杭州东/杭东 → 杭州东站</li>
 *   <li>金额归一化：二百/200/两百 → 200.0</li>
 *   <li>时间窗口：下午/下午三点左右/凌晨 → 标准时间段</li>
 *   <li>常见错别字/语音转写纠错：杭洲→杭州、红桥→虹桥</li>
 * </ul>
 * </p>
 */
@Service
public class EntityNormalizer {

    private static final Logger log = LoggerFactory.getLogger(EntityNormalizer.class);

    // ==================== 地点别名映射 ====================

    private final Map<String, String> stationAliases = new LinkedHashMap<>();
    private final Map<String, String> cityAliases = new LinkedHashMap<>();

    // ==================== 纠错映射 ====================

    private final Map<String, String> correctionMap = new LinkedHashMap<>();

    // ==================== 时间/日期 Pattern ====================

    private static final Pattern DATE_RELATIVE_PATTERN =
            Pattern.compile("(今|明|后|大后|昨|前)(天|日|晚|早|晨)");
    private static final Pattern DATE_WEEKDAY_PATTERN =
            Pattern.compile("(这|本|下|上)(周|星期)([一二三四五六日天])");
    private static final Pattern DATE_NUMERIC_PATTERN =
            Pattern.compile("(\\d{4})[年\\-](\\d{1,2})[月\\-](\\d{1,2})[日号]?");
    private static final Pattern TIME_RANGE_PATTERN =
            Pattern.compile("(凌晨|早上|上午|中午|下午|傍晚|晚上|半夜)");
    private static final Pattern AMOUNT_CHINESE_PATTERN =
            Pattern.compile("([一二两三四五六七八九十百千万亿]+)(块|元|毛|分)?");

    /** 时间窗口定义 */
    private static final Map<String, String[]> TIME_WINDOWS = new LinkedHashMap<>();
    static {
        TIME_WINDOWS.put("凌晨", new String[]{"00:00", "06:00"});
        TIME_WINDOWS.put("早上", new String[]{"06:00", "09:00"});
        TIME_WINDOWS.put("上午", new String[]{"09:00", "12:00"});
        TIME_WINDOWS.put("中午", new String[]{"11:00", "13:00"});
        TIME_WINDOWS.put("下午", new String[]{"12:00", "18:00"});
        TIME_WINDOWS.put("傍晚", new String[]{"17:00", "19:00"});
        TIME_WINDOWS.put("晚上", new String[]{"18:00", "23:59"});
        TIME_WINDOWS.put("半夜", new String[]{"23:00", "03:00"});
    }

    /** 星期映射 */
    private static final Map<String, Integer> WEEKDAY_MAP = new LinkedHashMap<>();
    static {
        WEEKDAY_MAP.put("一", 1); WEEKDAY_MAP.put("二", 2);
        WEEKDAY_MAP.put("三", 3); WEEKDAY_MAP.put("四", 4);
        WEEKDAY_MAP.put("五", 5); WEEKDAY_MAP.put("六", 6);
        WEEKDAY_MAP.put("日", 7); WEEKDAY_MAP.put("天", 7);
    }

    @PostConstruct
    public void init() {
        // ---- 车站别名 ----
        stationAliases.put("杭州东", "杭州东站");
        stationAliases.put("杭东", "杭州东站");
        stationAliases.put("杭州南", "杭州南站");
        stationAliases.put("杭州西", "杭州西站");
        stationAliases.put("上海虹桥", "上海虹桥站");
        stationAliases.put("上海站", "上海站");
        stationAliases.put("上海南", "上海南站");
        stationAliases.put("上海西", "上海西站");
        stationAliases.put("北京南", "北京南站");
        stationAliases.put("北京西", "北京西站");
        stationAliases.put("北京站", "北京站");
        stationAliases.put("广州南", "广州南站");
        stationAliases.put("深圳北", "深圳北站");

        // ---- 城市别名 ----
        cityAliases.put("北京", "北京市");
        cityAliases.put("上海", "上海市");
        cityAliases.put("广州", "广州市");
        cityAliases.put("深圳", "深圳市");
        cityAliases.put("杭州", "杭州市");
        cityAliases.put("bj", "北京市");
        cityAliases.put("sh", "上海市");
        cityAliases.put("gz", "广州市");
        cityAliases.put("sz", "深圳市");
        cityAliases.put("hz", "杭州市");

        // ---- 纠错映射 ----
        correctionMap.put("杭洲", "杭州");
        correctionMap.put("红桥", "虹桥");
        correctionMap.put("虹桥机场", "上海虹桥");
        correctionMap.put("上海虹桥机场", "上海虹桥");
        correctionMap.put("杭卅", "杭州");
        correctionMap.put("深玔", "深圳");
        correctionMap.put("广洲", "广州");
        correctionMap.put("北晶", "北京");
        correctionMap.put("坐位", "座位");
        correctionMap.put("坐票", "座位");
        correctionMap.put("站票", "无座");
    }

    /**
     * 对输入文本进行纠错标准化。
     *
     * @param input 原始输入
     * @return 标准化结果，包含修正后的文本和纠错记录
     */
    public NormalizationResult normalizeInput(String input) {
        if (input == null || input.isBlank()) {
            return new NormalizationResult(input, Collections.emptyList(), Collections.emptyList());
        }

        String normalized = input;
        List<Map<String, Object>> corrections = new ArrayList<>();
        List<String> noiseTypes = new ArrayList<>();

        // 1. 错别字纠错
        for (Map.Entry<String, String> entry : correctionMap.entrySet()) {
            String original = entry.getKey();
            String corrected = entry.getValue();
            if (normalized.contains(original)) {
                normalized = normalized.replace(original, corrected);
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("original", original);
                record.put("corrected", corrected);
                record.put("type", "typo");
                corrections.add(record);
                if (!noiseTypes.contains("typo")) noiseTypes.add("typo");
            }
        }

        // 2. 地名标准化
        for (Map.Entry<String, String> entry : stationAliases.entrySet()) {
            String alias = entry.getKey();
            String standard = entry.getValue();
            // 只替换独立的别名（避免误替换）
            if (normalized.contains(alias)) {
                normalized = normalized.replace(alias, standard);
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("original", alias);
                record.put("corrected", standard);
                record.put("type", "normalization");
                corrections.add(record);
            }
        }

        // 3. 检测省略噪声
        if (normalized.length() < input.length() * 0.8) {
            // 输入被显著缩短，说明有省略表达
            if (!noiseTypes.contains("omission")) noiseTypes.add("omission");
        }

        if (corrections.isEmpty() && !noiseTypes.isEmpty()) {
            noiseTypes.clear();
        }

        return new NormalizationResult(normalized, corrections, noiseTypes);
    }

    /**
     * 归一化日期表达。
     *
     * @param dateExpr 日期自然语言表达（如"明天"、"下周一"、"2026年4月23日"）
     * @return 归一化后的日期字符串（yyyy-MM-dd），无法解析时返回 null
     */
    public String normalizeDate(String dateExpr) {
        if (dateExpr == null || dateExpr.isBlank()) return null;

        LocalDate today = LocalDate.now();
        String trimmed = dateExpr.trim();

        // 1. 相对日期：明天/后天/大后天/昨天
        Matcher relativeMatcher = DATE_RELATIVE_PATTERN.matcher(trimmed);
        if (relativeMatcher.matches()) {
            String prefix = relativeMatcher.group(1);
            switch (prefix) {
                case "今": return today.format(DateTimeFormatter.ISO_LOCAL_DATE);
                case "明": return today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
                case "后": return today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE);
                case "大后": return today.plusDays(3).format(DateTimeFormatter.ISO_LOCAL_DATE);
                case "昨": return today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
                case "前": return today.minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
        }

        // 2. 星期：下周一、这周五
        Matcher weekdayMatcher = DATE_WEEKDAY_PATTERN.matcher(trimmed);
        if (weekdayMatcher.matches()) {
            String direction = weekdayMatcher.group(1);
            String dayStr = weekdayMatcher.group(3);
            Integer targetDay = WEEKDAY_MAP.get(dayStr);
            if (targetDay != null) {
                int currentDayOfWeek = today.getDayOfWeek().getValue(); // Mon=1, Sun=7
                int diff = targetDay - currentDayOfWeek;
                switch (direction) {
                    case "这":
                    case "本":
                        if (diff < 0) diff += 7; // 还在本周
                        break;
                    case "下":
                        diff += 7;
                        break;
                    case "上":
                        diff -= 7;
                        break;
                }
                return today.plusDays(diff).format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
        }

        // 3. 数字日期：2026年4月23日、2026-04-23
        Matcher numericMatcher = DATE_NUMERIC_PATTERN.matcher(trimmed);
        if (numericMatcher.find()) {
            int year = Integer.parseInt(numericMatcher.group(1));
            int month = Integer.parseInt(numericMatcher.group(2));
            int day = Integer.parseInt(numericMatcher.group(3));
            try {
                return LocalDate.of(year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                return null;
            }
        }

        // 4. 简单月日：4月23日
        Pattern mdPattern = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?");
        Matcher mdMatcher = mdPattern.matcher(trimmed);
        if (mdMatcher.find()) {
            int month = Integer.parseInt(mdMatcher.group(1));
            int day = Integer.parseInt(mdMatcher.group(2));
            try {
                LocalDate date = LocalDate.of(today.getYear(), month, day);
                if (date.isBefore(today)) {
                    date = date.plusYears(1);
                }
                return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                return null;
            }
        }

        return null;
    }

    /**
     * 归一化时间窗口表达。
     *
     * @param timeExpr 时间自然语言表达（如"下午"、"下午三点左右"、"14:00"）
     * @return 标准时间窗口 [start, end]，无法解析时返回 null
     */
    public String[] normalizeTimeWindow(String timeExpr) {
        if (timeExpr == null || timeExpr.isBlank()) return null;

        String trimmed = timeExpr.trim();

        // 1. 固定时间段
        for (Map.Entry<String, String[]> entry : TIME_WINDOWS.entrySet()) {
            if (trimmed.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 2. 具体时间点：14:00、下午3点、三点
        Pattern timePattern = Pattern.compile("(\\d{1,2})[:点时](\\d{2})?(?:分)?");
        Matcher matcher = timePattern.matcher(trimmed);
        if (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            // 如果有下午/晚上修饰且 hour <= 12，加12
            if ((trimmed.contains("下午") || trimmed.contains("晚上")
                    || trimmed.contains("傍晚")) && hour <= 12) {
                hour += 12;
            }
            String start = String.format("%02d:00", Math.max(0, hour - 1));
            String end = String.format("%02d:00", Math.min(23, hour + 1));
            return new String[]{start, end};
        }

        return null;
    }

    /**
     * 归一化金额表达。
     *
     * @param amountExpr 金额自然语言表达（如"二百"、"200"、"两百"）
     * @return 标准化金额数字，无法解析时返回 null
     */
    public Double normalizeAmount(String amountExpr) {
        if (amountExpr == null || amountExpr.isBlank()) return null;
        String trimmed = amountExpr.trim().replaceAll("[约大概左右]", "");

        // 1. 纯数字
        try {
            return Double.parseDouble(trimmed.replaceAll("[^\\d.]", ""));
        } catch (NumberFormatException ignored) {}

        // 2. 中文数字
        Matcher chMatcher = AMOUNT_CHINESE_PATTERN.matcher(trimmed);
        if (chMatcher.find()) {
            return chineseToNumber(chMatcher.group(1));
        }

        return null;
    }

    // ==================== 内部工具 ====================

    private Double chineseToNumber(String chinese) {
        Map<Character, Integer> digits = new HashMap<>();
        digits.put('零', 0); digits.put('一', 1); digits.put('二', 2);
        digits.put('两', 2); digits.put('三', 3); digits.put('四', 4);
        digits.put('五', 5); digits.put('六', 6); digits.put('七', 7);
        digits.put('八', 8); digits.put('九', 9); digits.put('十', 10);
        digits.put('百', 100); digits.put('千', 1000); digits.put('万', 10_000);
        digits.put('亿', 100_000_000);

        int result = 0;
        int current = 0;
        for (char c : chinese.toCharArray()) {
            Integer val = digits.get(c);
            if (val == null) continue;
            if (val >= 10) {
                if (current == 0) current = 1;
                result += current * val;
                current = 0;
            } else {
                current = val;
            }
        }
        result += current;
        return (double) result;
    }

    // ==================== 内部类 ====================

    /** 归一化结果 */
    public static class NormalizationResult {
        private final String normalizedText;
        private final List<Map<String, Object>> corrections;
        private final List<String> noiseTypes;

        public NormalizationResult(String normalizedText,
                                   List<Map<String, Object>> corrections,
                                   List<String> noiseTypes) {
            this.normalizedText = normalizedText;
            this.corrections = corrections;
            this.noiseTypes = noiseTypes;
        }

        public String getNormalizedText() { return normalizedText; }
        public List<Map<String, Object>> getCorrections() { return corrections; }
        public List<String> getNoiseTypes() { return noiseTypes; }
        public boolean hasCorrections() { return corrections != null && !corrections.isEmpty(); }
    }
}
