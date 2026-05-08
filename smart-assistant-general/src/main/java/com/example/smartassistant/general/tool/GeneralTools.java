package com.example.smartassistant.general.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 通用工具集 - 数学计算与单位转换
 *
 * <p>供 ReactAgent 调用的工具，覆盖日常实用场景。</p>
 */
@Component
public class GeneralTools {

    private static final Logger log = LoggerFactory.getLogger(GeneralTools.class);

    // ==================== 数学计算 ====================

    @Tool(description = "计算数学表达式，支持 + - * / () 和平方根(sqrt)、幂(pow)")
    public String calculate(
            @ToolParam(description = "数学表达式，如 '3.14 * 5^2'、'sqrt(144)'、'(12 + 8) * 3.5 / 2'") String expression) {
        log.info("[GeneralTools] 数学计算: expression={}", expression);
        try {
            double result = eval(expression);
            // 结果格式化：整数无小数，其他保留 6 位
            String formatted = formatResult(result);
            log.info("[GeneralTools] 计算结果: {} = {}", expression, formatted);
            return formatted;
        } catch (Exception e) {
            log.warn("[GeneralTools] 计算失败: {}", e.getMessage());
            return "无法计算该表达式，请检查格式是否正确。支持的运算符：+ - * / () ^ sqrt()";
        }
    }

    // ==================== 温度转换 ====================

    @Tool(description = "温度单位转换，支持 Celsius(°C)、Fahrenheit(°F)、Kelvin(K)")
    public String convertTemperature(
            @ToolParam(description = "数值") double value,
            @ToolParam(description = "源单位：C(摄氏)、F(华氏)、K(开尔文)") String fromUnit,
            @ToolParam(description = "目标单位：C(摄氏)、F(华氏)、K(开尔文)") String toUnit) {
        log.info("[GeneralTools] 温度转换: {} {} → {}", value, fromUnit, toUnit);
        try {
            // 先转成 Celsius
            double celsius;
            switch (fromUnit.toUpperCase()) {
                case "C": case "℃": case "°C": celsius = value; break;
                case "F": case "℉": case "°F": celsius = (value - 32) * 5 / 9; break;
                case "K": case "KELVIN": celsius = value - 273.15; break;
                default: return "不支持的温度单位: " + fromUnit + "，请使用 C/F/K";
            }

            // 从 Celsius 转到目标
            double result;
            String unit;
            switch (toUnit.toUpperCase()) {
                case "C": case "℃": case "°C": result = celsius; unit = "°C"; break;
                case "F": case "℉": case "°F": result = celsius * 9 / 5 + 32; unit = "°F"; break;
                case "K": case "KELVIN": result = celsius + 273.15; unit = "K"; break;
                default: return "不支持的温度单位: " + toUnit + "，请使用 C/F/K";
            }

            return formatResult(result) + unit;
        } catch (Exception e) {
            log.warn("[GeneralTools] 温度转换失败: {}", e.getMessage());
            return "温度转换失败，请检查输入格式";
        }
    }

    // ==================== 长度转换 ====================

    @Tool(description = "长度单位转换，支持 米(m)、千米(km)、厘米(cm)、毫米(mm)、英尺(ft)、英寸(in)、英里(mi)")
    public String convertLength(
            @ToolParam(description = "数值") double value,
            @ToolParam(description = "源单位：m/km/cm/mm/ft/in/mi") String fromUnit,
            @ToolParam(description = "目标单位：m/km/cm/mm/ft/in/mi") String toUnit) {
        log.info("[GeneralTools] 长度转换: {} {} → {}", value, fromUnit, toUnit);
        try {
            // 全部转成米
            double meters = toMeters(value, fromUnit);
            if (Double.isNaN(meters)) return "不支持的长度单位: " + fromUnit;

            // 从米转到目标
            double result = fromMeters(meters, toUnit);
            if (Double.isNaN(result)) return "不支持的长度单位: " + toUnit;

            return formatResult(result) + " " + toUnit.toLowerCase();
        } catch (Exception e) {
            log.warn("[GeneralTools] 长度转换失败: {}", e.getMessage());
            return "长度转换失败，请检查输入格式";
        }
    }

    // ==================== 重量转换 ====================

    @Tool(description = "重量单位转换，支持 千克(kg)、克(g)、毫克(mg)、磅(lb)、盎司(oz)、吨(t)")
    public String convertWeight(
            @ToolParam(description = "数值") double value,
            @ToolParam(description = "源单位：kg/g/mg/lb/oz/t") String fromUnit,
            @ToolParam(description = "目标单位：kg/g/mg/lb/oz/t") String toUnit) {
        log.info("[GeneralTools] 重量转换: {} {} → {}", value, fromUnit, toUnit);
        try {
            // 全部转成千克
            double kg = toKilograms(value, fromUnit);
            if (Double.isNaN(kg)) return "不支持的重量单位: " + fromUnit;

            // 从千克转到目标
            double result = fromKilograms(kg, toUnit);
            if (Double.isNaN(result)) return "不支持的重量单位: " + toUnit;

            return formatResult(result) + " " + toUnit.toLowerCase();
        } catch (Exception e) {
            log.warn("[GeneralTools] 重量转换失败: {}", e.getMessage());
            return "重量转换失败，请检查输入格式";
        }
    }

    // ==================== 新闻热点 ====================

    @Tool(description = "获取当前网络热门新闻热点话题（微博热搜、百度热点等），无需参数，自动获取最新热点。需要时政/综合热点时优先用此工具")
    public String getHotNews() {
        log.info("[GeneralTools] 获取热门新闻");
        long start = System.currentTimeMillis();
        try {
            // ⭐ 并行尝试多个来源，谁先返回就用谁
            var future1 = CompletableFuture.supplyAsync(() -> {
                String json = fetchJson("https://tenapi.cn/v2/hotlist");
                if (json != null) return formatHotNews(json);
                return null;
            });

            var future2 = CompletableFuture.supplyAsync(() -> {
                return fetchBaiduNews();
            });

            // 先到先用，最多等 4 秒
            String result = (String) CompletableFuture.anyOf(future1, future2)
                    .completeOnTimeout(null, 4, TimeUnit.SECONDS)
                    .get();

            log.info("[GeneralTools] 新闻获取完成, 耗时={}ms", System.currentTimeMillis() - start);
            if (result != null) return result;
            return "暂时无法获取新闻热点，请稍后再试";
        } catch (Exception e) {
            log.warn("[GeneralTools] 获取新闻失败: {}", e.getMessage());
            return "暂时无法获取新闻热点，请稍后再试";
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
            if (data != null && data.isArray() && data.size() > 0) {
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
                if (sb.length() > 0) return sb.toString();
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

    // ==================== 内部实现 ====================

    /**
     * 安全计算数学表达式（支持 + - * / ^ () sqrt）
     */
    private double eval(String expr) {
        return parseExpression(expr.replace(" ", ""));
    }

    private int pos;
    private String expr;

    private double parseExpression(String s) {
        this.expr = s;
        this.pos = 0;
        double result = parseAddSub();
        if (pos < expr.length()) {
            throw new RuntimeException("无法识别的字符: " + expr.charAt(pos));
        }
        return result;
    }

    private double parseAddSub() {
        double left = parseMulDiv();
        while (pos < expr.length()) {
            char c = expr.charAt(pos);
            if (c == '+') { pos++; left += parseMulDiv(); }
            else if (c == '-') { pos++; left -= parseMulDiv(); }
            else break;
        }
        return left;
    }

    private double parseMulDiv() {
        double left = parsePow();
        while (pos < expr.length()) {
            char c = expr.charAt(pos);
            if (c == '*') { pos++; left *= parsePow(); }
            else if (c == '/') { pos++; left /= parsePow(); }
            else break;
        }
        return left;
    }

    private double parsePow() {
        double left = parseUnary();
        if (pos < expr.length() && expr.charAt(pos) == '^') {
            pos++;
            double right = parsePow(); // right-associative
            left = Math.pow(left, right);
        }
        return left;
    }

    private double parseUnary() {
        if (pos < expr.length() && expr.charAt(pos) == '-') {
            pos++;
            return -parseAtom();
        }
        return parseAtom();
    }

    private double parseAtom() {
        // sqrt()
        if (pos + 4 < expr.length() && expr.startsWith("sqrt", pos)) {
            pos += 4;
            expect('(');
            double val = parseAddSub();
            expect(')');
            return Math.sqrt(val);
        }
        // 数字常量 pi/e
        if (pos + 2 <= expr.length() && expr.startsWith("pi", pos)) { pos += 2; return Math.PI; }
        if (pos < expr.length() && expr.charAt(pos) == 'e') { pos++; return Math.E; }
        // 括号
        if (pos < expr.length() && expr.charAt(pos) == '(') {
            pos++;
            double val = parseAddSub();
            expect(')');
            return val;
        }
        // 数字
        int start = pos;
        while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) pos++;
        if (pos == start) throw new RuntimeException("期望数字，但遇到: " + (pos < expr.length() ? expr.charAt(pos) : "结束"));
        return Double.parseDouble(expr.substring(start, pos));
    }

    private void expect(char c) {
        if (pos >= expr.length() || expr.charAt(pos) != c)
            throw new RuntimeException("期望 '" + c + "'，但遇到: " + (pos < expr.length() ? expr.charAt(pos) : "结束"));
        pos++;
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

    @Tool(description = "联网搜索信息，当用户问实时新闻、时事、百科知识或需要最新信息时调用")
    public String searchWeb(
            @ToolParam(description = "搜索关键词，如'2025年春节放假安排'") String query) {
        log.info("[GeneralTools] 联网搜索: query={}", query);
        try {
            // 使用 DuckDuckGo 搜索（无需 API Key）
            String url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8");
            String html = fetchJson(url);
            if (html == null) return "搜索失败，请稍后重试";

            return parseDuckDuckGoResults(html, query);
        } catch (Exception e) {
            log.warn("[GeneralTools] 搜索异常: {}", e.getMessage());
            return "搜索时发生错误: " + e.getMessage();
        }
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
            String link = html.substring(hrefStart, hrefEnd);

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
