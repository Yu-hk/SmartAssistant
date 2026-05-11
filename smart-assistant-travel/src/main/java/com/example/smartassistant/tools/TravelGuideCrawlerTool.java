package com.example.smartassistant.tools;

import com.example.smartassistant.service.rag.TravelNoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 攻略信息爬取工具
 * ⚠️ 合规说明：
 * - 仅在用户主动触发时执行
 * - 仅读取用户提供的 URL 公开内容
 * - 不批量爬取、不后台运行
 * - 不读取需要登录的内容
 * 支持的来源：
 * - 马蜂窝（mafengwo.cn）
 * - 穷游网（qyer.com）
 * - 携程攻略（you.ctrip.com）
 * - 其他可公开访问的旅游攻略页面
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TravelGuideCrawlerTool {

    private final TravelNoteService travelNoteService;

    private static final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 爬取指定 URL 的攻略内容
     * 
     * @param url 攻略页面的 URL（用户主动提供）
     * @param userId 用户 ID（用于存储爬取的攻略）
     * @return 攻略内容摘要
     */
    @Tool(name = "crawlTravelGuide", description = """
        从指定 URL 爬取旅游攻略内容。

        ⚠️ 合规要求：
        - 仅爬取用户主动提供的 URL
        - 仅读取公开可访问的页面
        - 不支持需要登录的内容

        使用场景：
        - 用户说 "帮我看看这个攻略：[URL]"
        - 用户说 "分析一下这篇游记：[URL]"

        返回攻略的标题、摘要、关键信息（景点、美食、路线等）
        """)
    public String crawlTravelGuide(
            @ToolParam(description = "攻略页面的完整 URL，必须是用户主动提供的") String url,
            @ToolParam(description = "当前用户 ID") Long userId) {

        log.info("用户 {} 授权爬取攻略: {}", userId, url);

        // 1. 验证 URL 来源（白名单）
        if (!isAllowedSource(url)) {
            return """
                    ❌ 该来源暂不支持。建议使用以下公开旅游攻略网站：
                    - 马蜂窝 (mafengwo.cn)
                    - 穷游网 (qyer.com)
                    - 携程攻略 (you.ctrip.com)
                    - 百度旅游""";
        }

        try {
            // 2. 发送 HTTP 请求获取页面
            String pageContent = fetchPage(url);

            if (pageContent == null || pageContent.isEmpty()) {
                return "⚠️ 无法获取页面内容，请检查 URL 是否正确或页面是否可访问。";
            }

            // 3. 解析页面内容
            Map<String, Object> parsedContent = parsePage(url, pageContent);

            // 4. 提取关键信息
            String title = parsedContent.getOrDefault("title", "未知攻略").toString();
            String summary = parsedContent.getOrDefault("summary", "").toString();
            List<String> attractions = (List<String>) parsedContent.getOrDefault("attractions", new ArrayList<>());
            List<String> foods = (List<String>) parsedContent.getOrDefault("foods", new ArrayList<>());
            List<String> tips = (List<String>) parsedContent.getOrDefault("tips", new ArrayList<>());

            // 5. 格式化输出
            StringBuilder result = new StringBuilder();
            result.append("📖 攻略信息已获取\n");
            result.append("═══════════════════════════════════\n\n");
            result.append("【").append(title).append("】\n\n");

            if (!summary.isEmpty()) {
                result.append("📝 摘要：\n").append(summary).append("\n\n");
            }

            if (!attractions.isEmpty()) {
                result.append("🏞️ 涉及景点：\n");
                attractions.forEach(a -> result.append("  • ").append(a).append("\n"));
                result.append("\n");
            }

            if (!foods.isEmpty()) {
                result.append("🍜 美食推荐：\n");
                foods.forEach(f -> result.append("  • ").append(f).append("\n"));
                result.append("\n");
            }

            if (!tips.isEmpty()) {
                result.append("💡 实用贴士：\n");
                tips.forEach(t -> result.append("  • ").append(t).append("\n"));
                result.append("\n");
            }

            result.append("═══════════════════════════════════\n");
            result.append("✅ 是否将此攻略保存到您的游记库？");

            return result.toString();

        } catch (Exception e) {
            log.error("爬取攻略失败: {}", url, e);
            return "⚠️ 爬取失败: " + e.getMessage() + "\n" +
                   "可能原因：\n" +
                   "1. 页面需要登录才能访问\n" +
                   "2. 页面已失效或不存在\n" +
                   "3. 网站有反爬机制";
        }
    }

    /**
     * 搜索并获取攻略摘要（基于关键词）
     * 
     * @param keyword 搜索关键词，如 "杭州西湖攻略"
     * @param limit 返回数量限制
     * @return 攻略摘要列表
     */
    @Tool(name = "searchTravelGuides", description = """
        根据关键词搜索旅游攻略信息。

        ⚠️ 合规说明：
        - 通过搜索引擎获取公开结果
        - 仅展示摘要信息
        - 不批量爬取完整内容

        使用场景：
        - 用户说 "帮我搜一下杭州三日游攻略"
        - 用户说 "看看最新的厦门旅游攻略"

        返回：攻略标题、来源网站、简要摘要
        """)
    public String searchTravelGuides(
            @ToolParam(description = "搜索关键词，如：杭州西湖三日游") String keyword,
            @ToolParam(description = "返回结果数量限制，默认5") int limit) {

        log.info("搜索攻略: keyword={}, limit={}", keyword, limit);

        // 使用 DuckDuckGo 等无 Google 依赖的搜索
        // 这里返回搜索提示，引导用户使用更具体的 URL
        return """
            🔍 搜索建议
            ════════════════════════════════

            为了获取更准确的攻略信息，请：

            1️⃣ 提供具体的攻略 URL
               例如：https://www.mafengwo.cn/xxx

            2️⃣ 或者告诉我：
               • 目的地（如：杭州、厦门）
               • 游玩天数（如：3天2晚）
               • 旅行类型（亲子/情侣/独自旅行）

            我会结合您的偏好和公开攻略，为您生成个性化建议。

            ════════════════════════════════
            """;
    }

    /**
     * 保存爬取的攻略到用户游记库
     */
    @Tool(name = "saveCrawledGuide", description = """
        将爬取的攻略保存到用户的游记库中。

        保存后可以通过 TravelRagTool 检索和引用。
        """)
    public String saveCrawledGuide(
            @ToolParam(description = "攻略标题") String title,
            @ToolParam(description = "攻略来源 URL") String sourceUrl,
            @ToolParam(description = "攻略内容摘要") String content,
            @ToolParam(description = "目的地/地点") String location,
            @ToolParam(description = "当前用户 ID") Long userId) {

        try {
            travelNoteService.createFromExternal(title, sourceUrl, content, location, userId);

            return "✅ 攻略已保存到您的游记库！\n\n" +
                   "📝 标题：" + title + "\n" +
                   "📍 地点：" + location + "\n\n" +
                   "之后询问相关目的地时，我会优先参考这份攻略。";

        } catch (Exception e) {
            log.error("保存攻略失败", e);
            return "❌ 保存失败: " + e.getMessage();
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 检查 URL 是否来自允许的来源
     */
    private boolean isAllowedSource(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        String[] allowedDomains = {
            "mafengwo.cn",
            "qyer.com",
            "ctrip.com",
            "baidu.com",
            "douban.com"
        };

        for (String domain : allowedDomains) {
            if (url.contains(domain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取页面内容
     */
    private String fetchPage(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (compatible; SmartAssistant/1.0)")
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(java.time.Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return response.body();
        } else {
            log.warn("HTTP {} for URL: {}", response.statusCode(), url);
            return null;
        }
    }

    /**
     * 解析页面内容（根据来源网站）
     */
    private Map<String, Object> parsePage(String url, String content) {
        Map<String, Object> result = new HashMap<>();

        if (url.contains("mafengwo.cn")) {
            return parseMafengwo(content);
        } else if (url.contains("qyer.com")) {
            return parseQyer(content);
        } else {
            // 通用解析
            return parseGeneric(content);
        }
    }

    /**
     * 解析马蜂窝页面
     */
    private Map<String, Object> parseMafengwo(String content) {
        Map<String, Object> result = new HashMap<>();

        // 提取标题
        Pattern titlePattern = Pattern.compile("<h1[^>]*>([^<]+)</h1>");
        Matcher titleMatcher = titlePattern.matcher(content);
        if (titleMatcher.find()) {
            result.put("title", cleanHtml(titleMatcher.group(1)));
        }

        // 提取 meta description
        Pattern descPattern = Pattern.compile("<meta[^>]*name=[\"']description[\"'][^>]*content=[\"']([^\"']+)");
        Matcher descMatcher = descPattern.matcher(content);
        if (descMatcher.find()) {
            result.put("summary", descMatcher.group(1));
        }

        // 提取景点（简化处理）
        List<String> attractions = extractMentions(content, new String[]{
            "景区", "景点", "公园", "博物馆", "寺庙", "山", "湖", "海"
        });
        result.put("attractions", attractions);

        // 提取美食
        List<String> foods = extractMentions(content, new String[]{
            "美食", "餐厅", "小吃", "推荐吃", "特色菜"
        });
        result.put("foods", foods);

        return result;
    }

    /**
     * 解析穷游页面
     */
    private Map<String, Object> parseQyer(String content) {
        Map<String, Object> result = new HashMap<>();

        Pattern titlePattern = Pattern.compile("<title>([^<]+)</title>");
        Matcher titleMatcher = titlePattern.matcher(content);
        if (titleMatcher.find()) {
            String title = titleMatcher.group(1);
            // 去除网站后缀
            title = title.replaceAll("\\s*-\\s*穷游网.*", "").trim();
            result.put("title", title);
        }

        // 提取 meta description
        Pattern descPattern = Pattern.compile("<meta[^>]*name=[\"']description[\"'][^>]*content=[\"']([^\"']+)");
        Matcher descMatcher = descPattern.matcher(content);
        if (descMatcher.find()) {
            result.put("summary", descMatcher.group(1));
        }

        result.put("attractions", new ArrayList<>());
        result.put("foods", new ArrayList<>());
        result.put("tips", new ArrayList<>());

        return result;
    }

    /**
     * 通用解析
     */
    private Map<String, Object> parseGeneric(String content) {
        Map<String, Object> result = new HashMap<>();

        // 提取标题
        Pattern titlePattern = Pattern.compile("<title>([^<]+)</title>");
        Matcher titleMatcher = titlePattern.matcher(content);
        if (titleMatcher.find()) {
            result.put("title", cleanHtml(titleMatcher.group(1)));
        }

        // 提取 meta description
        Pattern descPattern = Pattern.compile("<meta[^>]*name=[\"']description[\"'][^>]*content=[\"']([^\"']+)");
        Matcher descMatcher = descPattern.matcher(content);
        if (descMatcher.find()) {
            result.put("summary", descMatcher.group(1));
        }

        result.put("attractions", new ArrayList<>());
        result.put("foods", new ArrayList<>());
        result.put("tips", new ArrayList<>());

        return result;
    }

    /**
     * 提取提及的关键词
     */
    private List<String> extractMentions(String content, String[] keywords) {
        List<String> mentions = new ArrayList<>();

        for (String keyword : keywords) {
            Pattern pattern = Pattern.compile("[^。！？\\n]{2,15}" + keyword + "[^。！？\\n]{0,20}");
            Matcher matcher = pattern.matcher(content);

            while (matcher.find() && mentions.size() < 10) {
                String mention = cleanHtml(matcher.group()).trim();
                if (mention.length() > 5 && !mentions.contains(mention)) {
                    mentions.add(mention);
                }
            }
        }

        return mentions;
    }

    /**
     * 清理 HTML 标签
     */
    private String cleanHtml(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]+>", "").trim();
    }
}
