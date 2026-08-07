package com.example.smartassistant.common.intent;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic recognition for weather requests that require a city parameter. */
public final class WeatherQuerySupport {

    public static final String CITY_CLARIFICATION = "请告诉我想查询哪个城市的天气，例如“北京天气”。";

    private static final Pattern CHINESE_CITY = Pattern.compile(
            "([\\p{IsHan}]{1,12})(?=的?(?:今天|明天|后天)?(?:天气|气温|温度|天气预报))");
    private static final Pattern ENGLISH_CITY = Pattern.compile(
            "(?i)(?:weather|temperature)\\s+(?:in|for)\\s+([a-z][a-z .'-]{1,30})|([a-z][a-z .'-]{1,30})\\s+(?:weather|temperature)");
    private static final Pattern QUERY_WORDS = Pattern.compile(
            "查询|查一下|查查|看看|怎么样|如何|多少|预报|会不会|是否|什么|weather|temperature",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIXES = Pattern.compile(
            "^(?:请问|麻烦|帮我|帮忙|我想|想知道|看一下|看看|查一下|查询|查查|查|今天|明天|后天)+");
    private static final Pattern TIME_WORDS = Pattern.compile("今天|明天|后天|现在|当前|最近|未来");
    private static final Set<String> GENERIC_WORDS = Set.of(
            "", "天气", "气温", "温度", "预报", "天气预报", "查询", "查", "一下", "怎么样", "如何");

    private WeatherQuerySupport() {
    }

    public static boolean isWeatherLookup(String question) {
        if (question == null || question.isBlank()) return false;
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        boolean mentionsWeather = normalized.contains("天气") || normalized.contains("气温")
                || normalized.contains("温度") || normalized.contains("weather")
                || normalized.contains("temperature");
        if (!mentionsWeather) return false;
        return normalized.equals("天气") || normalized.equals("查天气")
                || QUERY_WORDS.matcher(normalized).find() || extractCity(normalized) != null;
    }

    public static boolean requiresCityClarification(String question) {
        return isWeatherLookup(question) && extractCity(question) == null;
    }

    public static String extractCity(String question) {
        if (question == null || question.isBlank()) return null;
        String normalized = question.trim().replaceAll("[？?！!，,。]", "");

        Matcher chinese = CHINESE_CITY.matcher(normalized);
        if (chinese.find()) {
            String candidate = sanitizeChineseCandidate(chinese.group(1));
            if (candidate != null) return candidate;
        }

        Matcher english = ENGLISH_CITY.matcher(normalized);
        if (english.find()) {
            String candidate = english.group(1) != null ? english.group(1) : english.group(2);
            candidate = candidate != null ? candidate.trim() : null;
            if (candidate != null && !candidate.isBlank()) return candidate;
        }
        return null;
    }

    private static String sanitizeChineseCandidate(String candidate) {
        String value = PREFIXES.matcher(candidate).replaceFirst("");
        value = TIME_WORDS.matcher(value).replaceAll("");
        if (value.endsWith("的")) value = value.substring(0, value.length() - 1);
        value = value.trim();
        if (value.endsWith("市") && value.length() > 2) {
            value = value.substring(0, value.length() - 1);
        }
        return value.length() >= 2 && !GENERIC_WORDS.contains(value) ? value : null;
    }
}
