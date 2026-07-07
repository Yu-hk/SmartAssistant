/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.tool;

import com.example.smartassistant.common.correction.CorrectionService;
import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.tool.ToolResult;
import com.example.smartassistant.general.sandbox.ScriptSandbox;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 通用工具集 - 数学计算与单位转换
 *
 * <p>供 ReactAgent 调用的工具，覆盖日常实用场景。</p>
 */
@Component
public class GeneralTools {

    private static final Logger log = LoggerFactory.getLogger(GeneralTools.class);

    private final CorrectionService correctionService;
    private final ScriptSandbox scriptSandbox;
    private final ToolRegistry toolRegistry;

    private final ExecutorService ioVirtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public GeneralTools(CorrectionService correctionService, ScriptSandbox scriptSandbox,
                        ToolRegistry toolRegistry) {
        this.correctionService = correctionService;
        this.scriptSandbox = scriptSandbox;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void initTools() {
        toolRegistry.registerAll(java.util.List.of(
                ToolDefinition.read("calculate", "数学表达式计算"),
                ToolDefinition.read("convertTemperature", "温度单位转换"),
                ToolDefinition.read("convertLength", "长度单位转换"),
                ToolDefinition.read("convertWeight", "重量单位转换"),
                ToolDefinition.write("getHotNews", "获取热点新闻", ToolRiskLevel.LOW),
                ToolDefinition.write("searchWeb", "联网搜索", ToolRiskLevel.LOW),
                ToolDefinition.write("convertCurrency", "货币汇率转换", ToolRiskLevel.LOW),
                ToolDefinition.read("queryCorrections", "查询历史纠错记录"),
                new ToolDefinition("executeScript", "执行多步计算脚本", ToolRiskLevel.MEDIUM,
                        java.time.Duration.ofSeconds(10), true, 1, 5, new String[0])
        ));
    }

    // ==================== 数学计算 ====================

    @Tool(description = "计算数学表达式，支持 + - * / () 和平方根(sqrt)、幂(pow)")
    public String calculate(
            @ToolParam(description = "数学表达式，如 '3.14 * 5^2'、'sqrt(144)'、'(12 + 8) * 3.5 / 2'", required = true) String expression) {
        log.info("[GeneralTools] 数学计算: expression={}", expression);
        try {
            double result = new ExpressionBuilder(expression)
                    .implicitMultiplication(false)
                    .build()
                    .evaluate();
            String formatted = formatResult(result);
            log.info("[GeneralTools] 计算结果: {} = {}", expression, formatted);
            return formatted;
        } catch (Exception e) {
            log.warn("[GeneralTools] 计算失败: {}", e.getMessage());
            return ToolResult.error(AgentErrorCode.VALIDATION_EXPRESSION_PARSE, "无法解析表达式",
                    "支持的运算符：+ - * / () ^ sqrt() abs() sin() cos() tan() log()");
        }
    }

    // ==================== 温度转换 ====================

    @Tool(description = "温度单位转换，支持 Celsius(°C)、Fahrenheit(°F)、Kelvin(K)")
    public String convertTemperature(
            @ToolParam(description = "数值", required = true) double value,
            @ToolParam(description = "源温度单位", required = true) TemperatureUnit fromUnit,
            @ToolParam(description = "目标温度单位", required = true) TemperatureUnit toUnit) {
        log.info("[GeneralTools] 温度转换: {} {} → {}", value, fromUnit, toUnit);
        try {
            // 先转成 Celsius
            double celsius;
            switch (fromUnit) {
                case C: celsius = value; break;
                case F: celsius = (value - 32) * 5 / 9; break;
                case K: celsius = value - 273.15; break;
                default: return ToolResult.error(AgentErrorCode.VALIDATION_CONVERSION_ERROR,
                        "不支持的温度单位: " + fromUnit,
                        "支持的源温度单位：C(°C)、F(°F)、K(K)");
            }

            // 从 Celsius 转到目标
            double result;
            String unitLabel;
            switch (toUnit) {
                case C: result = celsius; unitLabel = "°C"; break;
                case F: result = celsius * 9 / 5 + 32; unitLabel = "°F"; break;
                case K: result = celsius + 273.15; unitLabel = "K"; break;
                default: return ToolResult.error(AgentErrorCode.VALIDATION_CONVERSION_ERROR,
                        "不支持的目标温度单位: " + toUnit,
                        "支持的目标温度单位：C(°C)、F(°F)、K(K)");
            }

            return formatResult(result) + unitLabel;
        } catch (Exception e) {
            log.warn("[GeneralTools] 温度转换失败: {}", e.getMessage());
            return ToolResult.error(AgentErrorCode.VALIDATION_CONVERSION_ERROR, "温度转换失败");
        }
    }

    // ==================== 长度转换 ====================

    @Tool(description = "长度单位转换，支持 米(m)、千米(km)、厘米(cm)、毫米(mm)、英尺(ft)、英寸(in)、英里(mi)")
    public String convertLength(
            @ToolParam(description = "数值", required = true) double value,
            @ToolParam(description = "源长度单位", required = true) LengthUnit fromUnit,
            @ToolParam(description = "目标长度单位", required = true) LengthUnit toUnit) {
        log.info("[GeneralTools] 长度转换: {} {} → {}", value, fromUnit, toUnit);
        try {
            double meters = fromUnit.toMeters(value);
            double result = toUnit.fromMeters(meters);
            return formatResult(result) + " " + toUnit.getSymbol();
        } catch (Exception e) {
            log.warn("[GeneralTools] 长度转换失败: {}", e.getMessage());
            return ToolResult.error(AgentErrorCode.VALIDATION_CONVERSION_ERROR, "长度转换失败");
        }
    }

    // ==================== 重量转换 ====================

    @Tool(description = "重量单位转换，支持 千克(kg)、克(g)、毫克(mg)、磅(lb)、盎司(oz)、吨(t)")
    public String convertWeight(
            @ToolParam(description = "数值", required = true) double value,
            @ToolParam(description = "源重量单位", required = true) WeightUnit fromUnit,
            @ToolParam(description = "目标重量单位", required = true) WeightUnit toUnit) {
        log.info("[GeneralTools] 重量转换: {} {} → {}", value, fromUnit, toUnit);
        try {
            double kg = fromUnit.toKg(value);
            double result = toUnit.fromKg(kg);
            return formatResult(result) + " " + toUnit.getSymbol();
        } catch (Exception e) {
            log.warn("[GeneralTools] 重量转换失败: {}", e.getMessage());
            return ToolResult.error(AgentErrorCode.VALIDATION_CONVERSION_ERROR, "重量转换失败");
        }
    }

    // ==================== 新闻热点 ====================

    @Tool(description = "获取当前网络热门话题排行榜（微博热搜、百度热点排行榜）。仅返回热点榜单列表，不检索具体新闻内容。适用于用户想看'今天有什么热搜''最近热门话题'时调用。")
    public String getHotNews() {
        log.info("[GeneralTools] 获取热门新闻");
        long start = System.currentTimeMillis();
        try {
            // ⭐ 并行尝试多个来源，谁先返回就用谁
            var future1 = CompletableFuture.supplyAsync(() -> {
                String json = fetchJson("https://tenapi.cn/v2/hotlist");
                if (json != null) return formatHotNews(json);
                return null;
            }, ioVirtualExecutor);

            var future2 = CompletableFuture.supplyAsync(this::fetchBaiduNews, ioVirtualExecutor);

            // 先到先用，最多等 4 秒
            String result = (String) CompletableFuture.anyOf(future1, future2)
                    .completeOnTimeout(null, 4, TimeUnit.SECONDS)
                    .get();

            log.info("[GeneralTools] 新闻获取完成, 耗时={}ms", System.currentTimeMillis() - start);
            if (result != null) return result;
            return ToolResult.error(AgentErrorCode.SERVICE_NEWS_UNAVAILABLE, "暂无热点数据", "请稍后重试");
        } catch (Exception e) {
            log.warn("[GeneralTools] 获取新闻失败: {}", e.getMessage());
            return ToolResult.error(AgentErrorCode.SERVICE_NEWS_UNAVAILABLE, "新闻服务暂时不可用");
        }
    }

    /**
     * 从 HTTP API 获取 JSON
     */
    private String fetchJson(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            log.warn("[GeneralTools] fetchJson 失败: {} - {}", url, e.getMessage());
        }
        return null;
    }

    /**
     * 降级方案：从百度新闻首页提取热点
     */
    private String fetchBaiduNews() {
        String html = fetchJson("https://news.baidu.com/");
        if (html == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("📰 百度新闻热点\n\n");

        // 提取 hotnews 区域
        int idx = html.indexOf("hotnews");
        if (idx > 0) {
            int end = html.indexOf("</div>", idx);
            String section = html.substring(idx, end > 0 ? end : idx + 5000);

            // 提取每条新闻标题
            int count = 0;
            int pos = 0;
            while (count < 15) {
                int aStart = section.indexOf("<a", pos);
                if (aStart < 0) break;
                int titleStart = section.indexOf(">", aStart) + 1;
                int titleEnd = section.indexOf("</a>", titleStart);
                if (titleEnd < 0) break;
                String title = stripHtmlTags(section.substring(titleStart, titleEnd)).trim();
                if (title.length() > 4 && !title.contains("更多") && !title.contains("next")) {
                    count++;
                    sb.append("  ").append(count).append(". ").append(title).append("\n");
                }
                pos = titleEnd + 4;
            }
        }

        if (countItems(sb.toString()) < 3) {
            // 百度首页简单提取
            return fetchBaiduSimple(html);
        }
        return sb.toString();
    }

    /**
     * 最简降级：从百度首页 body 文本提取新闻相关词句
     */
    private String fetchBaiduSimple(String html) {
        StringBuilder sb = new StringBuilder();
        sb.append("📰 百度热点\n\n");
        String text = stripHtmlTags(html);
        int count = 0;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.length() > 8 && line.length() < 50 && !line.startsWith("http") && !line.contains(" ")) {
                count++;
                sb.append("  ").append(count).append(". ").append(line).append("\n");
                if (count >= 10) break;
            }
        }
        return count >= 3 ? sb.toString() : null;
    }

    /**
     * 格式化热点新闻（尝试解析 JSON，失败则直接当作文本）
     */
    private String formatHotNews(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            // 尝试解析 tenapi 格式: { code: 200, data: [ { name: "...", list: [...] } ] }
            JsonNode data = root.get("data");
            if (data != null && data.isArray() && !data.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode category : data) {
                    String name = category.get("name").asText("热点");
                    sb.append("🔥 ").append(name).append("\n\n");
                    JsonNode list = category.get("list");
                    if (list != null && list.isArray()) {
                        int count = 0;
                        for (JsonNode item : list) {
                            if (count >= 10) break;
                            String title = item.get("title").asText("");
                            if (title.isEmpty()) continue;
                            count++;
                            sb.append("  ").append(count).append(". ").append(title).append("\n");
                        }
                    }
                    sb.append("\n");
                }
                if (!sb.isEmpty()) return sb.toString();
            }

            // 尝试解析微博热榜格式: { data: { realtime: [...] } }
            JsonNode realtime = root.at("/data/realtime");
            if (realtime != null && realtime.isArray()) {
                StringBuilder sb = new StringBuilder("🔥 微博热搜\n\n");
                int count = 0;
                for (JsonNode item : realtime) {
                    if (count >= 15) break;
                    String word = item.get("word").asText("");
                    if (word.isEmpty()) continue;
                    count++;
                    sb.append("  ").append(count).append(". ").append(word).append("\n");
                }
                if (count > 0) return sb.toString();
            }

            // JSON 解析失败，直接当作文本返回摘要
            if (json.length() > 200) {
                return "📰 今日热点摘要\n\n" + json.substring(0, Math.min(json.length(), 1000)) + "\n...";
            }
        } catch (Exception e) {
            log.warn("[GeneralTools] 解析新闻 JSON 失败: {}", e.getMessage());
        }
        return null;
    }

    private String stripHtmlTags(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private int countItems(String text) {
        int count = 0;
        for (String line : text.split("\n")) {
            if (line.matches("\\s*\\d+\\.\\s.*")) count++;
        }
        return count;
    }

    // ==================== 单位转换辅助 ====================

    private double toMeters(double value, String unit) {
        return switch (unit.toLowerCase()) {
            case "m" -> value;
            case "km" -> value * 1000;
            case "cm" -> value / 100;
            case "mm" -> value / 1000;
            case "ft", "英尺" -> value * 0.3048;
            case "in", "英寸" -> value * 0.0254;
            case "mi", "英里" -> value * 1609.344;
            default -> Double.NaN;
        };
    }

    private double fromMeters(double meters, String unit) {
        return switch (unit.toLowerCase()) {
            case "m" -> meters;
            case "km" -> meters / 1000;
            case "cm" -> meters * 100;
            case "mm" -> meters * 1000;
            case "ft", "英尺" -> meters / 0.3048;
            case "in", "英寸" -> meters / 0.0254;
            case "mi", "英里" -> meters / 1609.344;
            default -> Double.NaN;
        };
    }

    private double toKilograms(double value, String unit) {
        return switch (unit.toLowerCase()) {
            case "kg", "千克" -> value;
            case "g", "克" -> value / 1000;
            case "mg", "毫克" -> value / 1_000_000;
            case "lb", "磅" -> value * 0.45359237;
            case "oz", "盎司" -> value * 0.028349523125;
            case "t", "吨" -> value * 1000;
            default -> Double.NaN;
        };
    }

    private double fromKilograms(double kg, String unit) {
        return switch (unit.toLowerCase()) {
            case "kg", "千克" -> kg;
            case "g", "克" -> kg * 1000;
            case "mg", "毫克" -> kg * 1_000_000;
            case "lb", "磅" -> kg / 0.45359237;
            case "oz", "盎司" -> kg / 0.028349523125;
            case "t", "吨" -> kg / 1000;
            default -> Double.NaN;
        };
    }

    @Tool(description = "联网搜索具体信息，适用于问答、百科查询、查找特定新闻原文等。注意：不要用此工具查热点排行榜，排行榜请用 getHotNews。当用户想了解某个具体事件/话题的详情时调用此工具。")
    public String searchWeb(
            @ToolParam(description = "搜索关键词，如'2025年春节放假安排'", required = true) String query) {
        log.info("[GeneralTools] 联网搜索: query={}", query);
        try {
            // 使用 DuckDuckGo 搜索（无需 API Key）
            String url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            String html = fetchJson(url);
            if (html == null) {
                log.warn("[GeneralTools] DuckDuckGo 搜索失败，尝试备用搜索源");
                return fallbackSearch(query);
            }

            String result = parseDuckDuckGoResults(html, query);
            // 如果 DuckDuckGo 返回空结果，尝试备用源
            if (result.contains("未找到") || result.contains("共找到 0 条")) {
                log.warn("[GeneralTools] DuckDuckGo 无结果，尝试备用搜索源");
                return fallbackSearch(query);
            }
            return result;
        } catch (Exception e) {
            log.warn("[GeneralTools] 搜索异常: {}, 尝试备用源", e.getMessage());
            return fallbackSearch(query);
        }
    }

    /**
     * 备用搜索源：当 DuckDuckGo 搜索失败时使用
     * 调用 tenapi 获取结果（无需 API Key）
     */
    private String fallbackSearch(String query) {
        try {
            String encoded = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://tenapi.cn/v2/search?keyword=" + encoded;
            String json = fetchJson(url);
            if (json == null) return fallbackBingSearch(query);

            // 解析 JSON 结果
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 搜索结果：「").append(query).append("」（备用源）\n\n");

            // 提取 data 数组
            int dataStart = json.indexOf("\"data\":[");
            if (dataStart < 0) return fallbackBingSearch(query);

            String dataSection = json.substring(dataStart + 7);
            int count = 0;
            int pos = 0;
            while (count < 6) {
                int itemStart = dataSection.indexOf("{", pos);
                if (itemStart < 0) break;

                // 提取 title
                int titleKey = dataSection.indexOf("\"title\":\"", itemStart);
                if (titleKey < 0) break;
                int titleValStart = titleKey + 9;
                int titleValEnd = dataSection.indexOf("\"", titleValStart);
                String title = dataSection.substring(titleValStart, titleValEnd);

                // 提取 desc
                int descKey = dataSection.indexOf("\"desc\":\"", itemStart);
                String snippet = "";
                if (descKey >= 0) {
                    int descValStart = descKey + 8;
                    int descValEnd = dataSection.indexOf("\"", descValStart);
                    snippet = dataSection.substring(descValStart, descValEnd);
                }

                count++;
                sb.append(count).append(". ").append(unescapeJson(title)).append("\n");
                if (!snippet.isEmpty()) {
                    sb.append("   ").append(unescapeJson(snippet)).append("\n\n");
                }
                pos = itemStart + 1;
            }

            if (count > 0) {
                sb.append("--- 共找到 ").append(count).append(" 条结果");
                return sb.toString();
            }
            return fallbackBingSearch(query);
        } catch (Exception e) {
            log.warn("[GeneralTools] 备用搜索也失败: {}", e.getMessage());
            return fallbackBingSearch(query);
        }
    }

    /**
     * 第三级降级：使用必应搜索（无需 API Key）
     */
    private String fallbackBingSearch(String query) {
        try {
            String encoded = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.bing.com/search?q=" + encoded + "&mkt=zh-CN";
            String html = fetchJson(url);
            if (html == null) {
                return ToolResult.error(AgentErrorCode.SERVICE_SEARCH_UNAVAILABLE, "搜索服务暂时不可用", "请稍后重试");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🔍 搜索结果：「").append(query).append("」（备用源）\n\n");

            int count = 0;
            int pos = 0;
            while (count < 6) {
                // 必应搜索结果块：<li class="b_algo">
                int resultStart = html.indexOf("<li class=\"b_algo\"", pos);
                if (resultStart < 0) break;

                // 提取标题：<h2><a href="..." ...>标题</a></h2>
                int h2Start = html.indexOf("<h2>", resultStart);
                if (h2Start < 0) { pos = resultStart + 1; continue; }
                int aStart = html.indexOf("<a ", h2Start);
                if (aStart < 0) { pos = resultStart + 1; continue; }
                int hrefStart = html.indexOf("href=\"", aStart);
                if (hrefStart < 0) { pos = resultStart + 1; continue; }
                hrefStart += 6;
                int hrefEnd = html.indexOf("\"", hrefStart);
                if (hrefEnd < 0) { pos = resultStart + 1; continue; }

                int titleTagEnd = html.indexOf("</a>", aStart);
                int titleTextStart = html.indexOf(">", hrefEnd + 1) + 1;
                if (titleTextStart > titleTagEnd || titleTextStart <= 0) { pos = resultStart + 1; continue; }
                String title = stripHtmlTags(html.substring(titleTextStart, titleTagEnd)).trim();

                // 提取摘要：<p class="b_lineclamp...">
                int pStart = html.indexOf("<p", titleTagEnd);
                if (pStart > 0) {
                    int pTagEnd = html.indexOf(">", pStart) + 1;
                    int pEnd = html.indexOf("</p>", pTagEnd);
                    String snippet = pEnd > pTagEnd ? stripHtmlTags(html.substring(pTagEnd, pEnd)).trim() : "";

                    count++;
                    sb.append(count).append(". ").append(title).append("\n");
                    if (!snippet.isEmpty()) {
                        sb.append("   ").append(snippet).append("\n\n");
                    }
                }
                pos = resultStart + 1;
            }

            if (count == 0) {
                return ToolResult.error(AgentErrorCode.NO_RESULTS, "未找到相关结果", "请尝试更换关键词");
            }
            sb.append("--- 共找到 ").append(count).append(" 条结果");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[GeneralTools] Bing 搜索也失败: {}", e.getMessage());
            return ToolResult.error(AgentErrorCode.SERVICE_SEARCH_UNAVAILABLE, "搜索服务暂时不可用", "请稍后重试");
        }
    }

    /**
     * 简单的 JSON 转义字符还原
     */
    private String unescapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\\"", "\"")
                   .replace("\\n", "\n")
                   .replace("\\t", "\t")
                   .replace("\\/", "/")
                   .replace("\\\\", "\\");
    }

    /**
     * 解析 DuckDuckGo HTML 搜索结果
     */
    private String parseDuckDuckGoResults(String html, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 搜索结果：「").append(query).append("」\n\n");

        int count = 0;
        int pos = 0;
        while (count < 8) {
            // 查找结果块
            int resultStart = html.indexOf("<a rel=\"nofollow\" class=\"result__a\" href=\"", pos);
            if (resultStart < 0) break;

            int hrefStart = resultStart + 44;
            int hrefEnd = html.indexOf("\"", hrefStart);

            int titleStart = html.indexOf(">", hrefEnd) + 1;
            int titleEnd = html.indexOf("</a>", titleStart);
            String title = stripHtmlTags(html.substring(titleStart, titleEnd)).trim();

            // 查找摘要
            int snippetStart = html.indexOf("<a class=\"result__snippet\"", resultStart);
            if (snippetStart < 0) break;
            int snippetTextStart = html.indexOf(">", snippetStart) + 1;
            int snippetEnd = html.indexOf("</a>", snippetTextStart);
            String snippet = stripHtmlTags(html.substring(snippetTextStart, snippetEnd)).trim();

            count++;
            sb.append(count).append(". ").append(title).append("\n");
            sb.append("   ").append(snippet).append("\n\n");

            pos = snippetEnd + 4;
        }

        if (count == 0) {
            return "未找到「" + query + "」的相关结果，请尝试换个关键词。";
        }

        sb.append("--- 共找到 ").append(count).append(" 条结果（由 DuckDuckGo 提供）");
        return sb.toString();
    }

    // ==================== 汇率转换 ====================

    @Tool(description = "货币汇率转换，支持 CNY/USD/EUR/GBP/JPY/HKD/KRW/THB/AUD/CAD 等主流货币，基于实时汇率。适用于'100美元等于多少人民币'、'500欧元兑日元'等场景")
    public String convertCurrency(
            @ToolParam(description = "金额数值", required = true) double value,
            @ToolParam(description = "源货币代码，如 USD、CNY、EUR、JPY 等", required = true) String fromCurrency,
            @ToolParam(description = "目标货币代码，如 CNY、USD、EUR、GBP 等", required = true) String toCurrency) {
        log.info("[GeneralTools] 汇率转换: {} {} → {}", value, fromCurrency, toCurrency);
        try {
            String from = fromCurrency.trim().toUpperCase();
            String to = toCurrency.trim().toUpperCase();

            // 相同货币直接返回
            if (from.equals(to)) {
                return formatResult(value) + " " + to;
            }

            // 获取源货币的实时汇率
            String url = "https://open.er-api.com/v6/latest/" + from;
            String json = fetchJson(url);
            if (json == null) {
                return ToolResult.error(AgentErrorCode.SERVICE_RATE_UNAVAILABLE, "获取汇率失败", "请稍后重试");
            }

            // 解析 JSON 提取目标汇率
            String searchKey = "\"" + to + "\":";
            int idx = json.indexOf(searchKey);
            if (idx == -1) {
                return ToolResult.error(AgentErrorCode.VALIDATION_INVALID_CURRENCY, "不支持的货币代码: " + to,
                        "支持的货币：CNY/USD/EUR/GBP/JPY/HKD/KRW/THB/AUD/CAD");
            }

            int start = idx + searchKey.length();
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            if (end == -1) end = json.length();

            double rate = Double.parseDouble(json.substring(start, end).trim());
            double result = value * rate;

            return formatResult(result) + " " + to + "（当前汇率: 1 " + from + " = " + formatResult(rate) + " " + to + "）";
        } catch (Exception e) {
            log.warn("[GeneralTools] 汇率转换失败: {}", e.getMessage());
            return ToolResult.error(AgentErrorCode.VALIDATION_CONVERSION_ERROR, "汇率转换失败");
        }
    }

    /**
     * 查询历史纠错记录
     */
    @Tool(description = "查询本 Agent 的历史纠错记录。在回答事实性问题前先调用此工具，检查是否有已被用户纠正过的信息，避免重复错误。")
    public String queryCorrections(
            @ToolParam(description = "查询主题，如'世界最高峰'、'中国人口'，空字符串则返回全部", required = false) String topic) {
        log.info("[GeneralTools] 查询修正记录: topic={}", topic);
        String result = correctionService.queryCorrections("general", topic != null ? topic : "");
        if (result.isBlank()) {
            return "未找到相关的修正记录，可以按正常流程回答。";
        }
        return result;
    }

    // ==================== 多步脚本执行 ====================

    /**
     * 多步计算脚本执行器。
     *
     * <p>支持多行赋值脚本，每行可以是：</p>
     * <ul>
     *   <li>变量赋值：{@code x = 表达式}，支持引用前面定义的变量</li>
     *   <li>纯表达式：{@code 表达式}，直接计算并输出结果</li>
     *   <li>注释：以 {@code #} 开头的行，显示为说明文字</li>
     * </ul>
     *
     * <p>示例：</p>
     * <pre>
     *   # 计算复利本息
     *   principal = 10000
     *   rate = 0.045
     *   years = 5
     *   result = principal * (1 + rate)^years
     * </pre>
     */
    @Tool(description = "执行多步计算脚本，支持变量赋值和跨行引用，适用于复利计算、工程公式、分步推导等复杂场景。格式：每行一条语句，'变量名 = 表达式' 或直接写表达式，# 开头为注释。示例：'r=0.05\\nyears=10\\nresult=10000*(1+r)^years'")
    public String executeScript(
            @ToolParam(description = "多行计算脚本，换行用 \\n 分隔，如 'a=3\\nb=4\\nc=sqrt(a^2+b^2)'", required = true) String script) {
        log.info("[GeneralTools] 执行计算脚本: script={}",
                script == null ? "<null>" : script.replace("\n", " | "));

        // 委派给运行时沙箱：资源限制（长度/行数/变量数）+ 超时熔断 + 危险关键字拦截
        ScriptSandbox.SandboxResult result = scriptSandbox.execute(script);
        if (result.success()) {
            return result.output();
        }
        return ToolResult.error(result.errorCode(), result.message(), result.hint());
    }

    private String formatResult(double value) {
        if (Double.isInfinite(value)) return "结果无穷大";
        if (Double.isNaN(value))      return "结果不是有效数字";
        // 整数无小数
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        // 保留 6 位小数，去除末尾多余的 0
        BigDecimal bd = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
        return bd.stripTrailingZeros().toPlainString();
    }
}
