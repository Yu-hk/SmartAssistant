package com.example.smartassistant.router.strategy;

import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.RouteDecision;
import com.example.smartassistant.router.service.AgentDiscoveryService;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键字 + 语义匹配路由策略
 * <p>
 * 替代 ReactAgent LLM 路由，直接根据用户输入中的关键字和各 Agent 的注册元数据进行匹配。
 * 同时从用户输入中提取地点、时间等上下文信息。
 * <p>
 * 匹配优先级：
 * <ol>
 *   <li>精确关键字匹配（40%）：用户输入包含 Agent 定义的关键词</li>
 *   <li>能力语义匹配（30%）：支持的功能与用户意图匹配</li>
 *   <li>Agent 优先级权重（20%）：注册时配置的 priority 字段</li>
 *   <li>上下文继承（10%）：会话历史中的 Agent 连续性</li>
 * </ol>
 */
@Slf4j
@Component
public class KeywordRoutingStrategy implements RoutingStrategy {

    // ===================== 关键字定义（与 Agent 元数据对应）=====================

    /** 天气意图关键字 */
    private static final Set<String> WEATHER_KEYWORDS = Set.of(
            "天气", "气温", "温度", "下雨", "晴天", "weather", "temperature",
            "热不热", "冷不冷", "穿什么", "要不要带伞", "会不会下雨"
    );

    /** 美食意图关键字 */
    private static final Set<String> FOOD_KEYWORDS = Set.of(
            "美食", "吃", "餐厅", "food", "好吃的", "特色菜", "菜系",
            "火锅", "烧烤", "川菜", "粤菜", "鲁菜", "湘菜", "推荐餐厅",
            "去哪吃", "附近有什么好吃的", "美食推荐"
    );

    /** 出行/旅游意图关键字 */
    private static final Set<String> TRAVEL_KEYWORDS = Set.of(
            "旅游", "旅行", "景点", "攻略", "带娃", "亲子", "出游",
            "出行", "行程", "路线", "travel", "trip", "去哪里", "玩",
            "动物园", "乐园", "博物馆", "公园", "西湖", "游乐园"
    );

    /** 位置意图关键字 */
    private static final Set<String> LOCATION_KEYWORDS = Set.of(
            "在哪里", "地址", "怎么去", "距离", "附近", "位置"
    );

    /** 规划意图关键字 */
    private static final Set<String> PLANNING_KEYWORDS = Set.of(
            "规划", "计划", "安排", "建议", "推荐", "定制"
    );

    // ===================== 地点提取正则 =====================

    private static final Pattern CITY_PATTERN = Pattern.compile(
            "[在去到去从]" +
                    "(北京|上海|广州|深圳|杭州|南京|成都|重庆|西安|武汉|天津|苏州|郑州|" +
                    "长沙|东莞|青岛|沈阳|宁波|昆明|大连|厦门|福州|哈尔滨|长春|" +
                    "石家庄|南昌|合肥|济南|温州|南宁|贵阳|太原|嘉兴|惠州|" +
                    "常州|徐州|南通|扬州|烟台|唐山|潍坊|临沂|淄博|绍兴|" +
                    "台州|镇江|盐城|淮安|泰州|连云港|宿迁|沧州|廊坊|秦皇岛)" +
                    "(?:玩|市|城|区|附近|有没有)?"
    );

    private static final Pattern PROVINCE_PATTERN = Pattern.compile(
            "[在去到]" +
                    "(河北|浙江|江苏|四川|广东|山东|河南|湖北|湖南|陕西|" +
                    "安徽|福建|江西|云南|辽宁|吉林|黑龙江|贵州|山西|海南)" +
                    "(?:省|有什么)?"
    );

    // ===================== 时间提取 =====================

    private static final Map<String, String> TIME_KEYWORDS = Map.ofEntries(
            Map.entry("今天", "今天"),
            Map.entry("明天", "明天"),
            Map.entry("后天", "后天"),
            Map.entry("大后天", "大后天"),
            Map.entry("这周末", "周末"),
            Map.entry("周末", "周末"),
            Map.entry("下周", "下周"),
            Map.entry("下个月", "下个月"),
            Map.entry("什么时候", "未知"),
            Map.entry("最近", "近期")
    );

    // ===================== 出行场景细分 =====================

    /** 带娃/亲子场景 */
    private static final Set<String> FAMILY_KEYWORDS = Set.of(
            "带娃", "亲子", "孩子", "小朋友", "小孩", "宝宝", "儿童",
            "一家三口", "全家", "亲子游", "溜娃"
    );

    /** 情侣/浪漫场景 */
    private static final Set<String> COUPLE_KEYWORDS = Set.of(
            "情侣", "约会", "浪漫", "二人世界", "蜜月"
    );

    /** 商务场景 */
    private static final Set<String> BUSINESS_KEYWORDS = Set.of(
            "商务", "出差", "会议", "公干"
    );

    private final AgentDiscoveryService agentDiscoveryService;
    private final ChineseTokenizer tokenizer;

    /** 是否启用中文分词器增强 */
    @Value("${router.tokenizer.enabled:true}")
    private boolean tokenizerEnabled;

    public KeywordRoutingStrategy(AgentDiscoveryService agentDiscoveryService, 
                                   ChineseTokenizer tokenizer) {
        this.agentDiscoveryService = agentDiscoveryService;
        this.tokenizer = tokenizer;
    }

    @Override
    public RouteDecision route(String userInput, Map<String, Object> context) {
        if (userInput == null || userInput.trim().isEmpty()) {
            log.warn("[KeywordRouting] 用户输入为空");
            return null;
        }
        log.debug("[KeywordRouting] 执行关键字路由: inputLength={}", userInput.length());

        // Step 1: 提取上下文（地点、时间）
        RouteDecision.ExtractedContext extractedContext = extractContext(userInput);

        // Step 2: 获取所有可用 Agent
        List<DiscoveredAgent> agents = agentDiscoveryService.discoverAllAgents();
        if (agents.isEmpty()) {
            log.warn("[KeywordRouting] 未发现可用 Agent");
            return null;
        }

        // Step 3: 计算每个 Agent 的匹配得分
        Map<String, Double> scores = new HashMap<>();
        for (DiscoveredAgent agent : agents) {
            double score = calculateScore(userInput, context, agent, extractedContext);
            scores.put(agent.getAgentName(), score);
            log.debug("[KeywordRouting] Agent {} 得分: {}", agent.getAgentName(), score);
        }

        // Step 4: 选择得分最高的 Agent
        Optional<Map.Entry<String, Double>> best = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue());

        if (best.isEmpty() || best.get().getValue() < 30.0) {
            log.warn("[KeywordRouting] 所有 Agent 得分均低于阈值（30），放弃路由");
            return null;
        }

        String bestAgent = best.get().getKey();
        double bestScore = best.get().getValue();
        double confidence = Math.min(bestScore / 100.0, 1.0);

        // Step 5: 构建路由决策
        RouteDecision decision = RouteDecision.builder()
                .agentName(bestAgent)
                .confidence(confidence)
                .routingMethod("KEYWORD_ROUTING")
                .reason(buildReason(userInput, bestAgent, scores.get(bestAgent)))
                .extractedContext(extractedContext)
                .build();

        log.info("[KeywordRouting] 路由完成: agent={}, score={}, confidence={}, location={}, intent={}",
                bestAgent, bestScore, confidence, extractedContext.getLocation(), extractedContext.getIntent());

        return decision;
    }

    @Override
    public String getStrategyName() {
        return "KEYWORD_ROUTING";
    }

    @Override
    public int getPriority() {
        return 1; // ⭐ 最高优先级，优先于 SmartRouting (3) 和 ReactAgentRouting (99)
    }

    // ===================== 核心评分算法 =====================

    /**
     * 计算 Agent 匹配得分（满分 100）
     */
    private double calculateScore(String userInput, Map<String, Object> context,
                                  DiscoveredAgent agent, RouteDecision.ExtractedContext extractedContext) {
        double score = 0.0;
        String lowerInput = userInput.toLowerCase();
        AgentMetadata meta = agent.getMetadata();

        // ★ 权重1（40%）: 精确关键字匹配
        double keywordScore = calculateKeywordScore(lowerInput, meta);
        score += keywordScore * 0.40;

        // ★ 权重2（30%）: 能力语义匹配
        double capabilityScore = calculateCapabilityScore(lowerInput, meta);
        score += capabilityScore * 0.30;

        // ★ 权重3（20%）: Agent 优先级
        int agentPriority = (meta != null && meta.getPriority() != null) ? meta.getPriority() : 5;
        double priorityScore = Math.min(agentPriority * 2.0, 20.0); // 最高 20 分
        score += priorityScore;

        // ★ 权重4（10%）: 上下文继承（会话连续性）
        double contextScore = calculateContextScore(context, agent.getAgentName());
        score += contextScore * 0.10;

        log.debug("[KeywordRouting] 详细评分 {}: kw={}(x0.4={}), cap={}(x0.3={}), pri={}, ctx={}(x0.1={}), total={}",
                agent.getAgentName(),
                String.format("%.1f", keywordScore),
                String.format("%.1f", keywordScore * 0.40),
                String.format("%.1f", capabilityScore),
                String.format("%.1f", capabilityScore * 0.30),
                String.format("%.1f", priorityScore),
                String.format("%.1f", contextScore),
                String.format("%.1f", contextScore * 0.10),
                String.format("%.1f", score));

        return Math.min(score, 100.0);
    }

    /**
     * 关键字得分：根据 Agent 元数据中的 keywords 字段匹配
     * <p>
     * 增强：使用分词器提升关键词召回率
     */
    private double calculateKeywordScore(String lowerInput, AgentMetadata meta) {
        if (meta == null || meta.getKeywords() == null || meta.getKeywords().isEmpty()) {
            // Fallback：使用内置关键字判断
            return calculateFallbackKeywordScore(lowerInput);
        }

        String[] keywords = meta.getKeywordsArray();
        
        // 使用分词器增强计数
        int matchCount = countKeywordMatches(lowerInput, Set.of(keywords));

        if (matchCount == 0) return 0;
        // 命中 1 个关键字给 40 分，每增加 1 个 +15 分，上限 100
        return Math.min(40.0 + (matchCount - 1) * 15.0, 100.0);
    }

    /**
     * 内置关键字兜底（Agent 未注册 keywords 时使用）
     */
    private double calculateFallbackKeywordScore(String lowerInput) {
        double score = 0.0;

        if (matchesAny(lowerInput, WEATHER_KEYWORDS)) score += 40;
        if (matchesAny(lowerInput, FOOD_KEYWORDS)) score += 40;
        if (matchesAny(lowerInput, TRAVEL_KEYWORDS)) score += 40;
        if (matchesAny(lowerInput, PLANNING_KEYWORDS)) score += 20;

        return Math.min(score, 100.0);
    }

    /**
     * 能力语义得分：根据 Agent 支持的能力字段匹配
     */
    private double calculateCapabilityScore(String lowerInput, AgentMetadata meta) {
        if (meta == null) return 0.0;

        double score = 0.0;

        if (Boolean.TRUE.equals(meta.getSupportWeather()) && matchesAny(lowerInput, WEATHER_KEYWORDS)) {
            score += 50;
        }
        if (Boolean.TRUE.equals(meta.getSupportPlanning()) && matchesAny(lowerInput, TRAVEL_KEYWORDS)) {
            score += 50;
        }
        if (Boolean.TRUE.equals(meta.getSupportLocation()) && matchesAny(lowerInput, LOCATION_KEYWORDS)) {
            score += 30;
        }

        // 能力数组匹配
        for (String cap : meta.getCapabilitiesArray()) {
            String capLower = cap.trim().toLowerCase();
            if (capLower.contains("weather") && matchesAny(lowerInput, WEATHER_KEYWORDS)) score += 30;
            // food 相关能力：检查 capability 名称是否包含 food/cuisine/restaurant/food 等关键词
            if ((capLower.contains("food") || capLower.contains("cuisine") || capLower.contains("restaurant"))
                    && matchesAny(lowerInput, FOOD_KEYWORDS)) score += 30;
            if (capLower.contains("travel") && matchesAny(lowerInput, TRAVEL_KEYWORDS)) score += 30;
            if (capLower.contains("planning") && matchesAny(lowerInput, PLANNING_KEYWORDS)) score += 20;
        }

        return Math.min(score, 100.0);
    }

    /**
     * 上下文继承得分
     */
    private double calculateContextScore(Map<String, Object> context, String agentName) {
        if (context == null) return 0.0;

        double score = 0.0;

        // 会话连续性：上次使用的 Agent
        Object lastAgent = context.get("currentAgent");
        if (lastAgent != null && lastAgent.toString().equals(agentName)) {
            score += 60;
        }

        // 上下文中的城市信息：location/weather/travel/food agent 都受益
        if (context.containsKey("currentCity") && context.get("currentCity") != null) {
            if (agentName.contains("location") || agentName.contains("weather") ||
                agentName.contains("travel") || agentName.contains("food")) {
                score += 30;
            }
        }

        return Math.min(score, 100.0);
    }

    // ===================== 上下文提取 =====================

    /**
     * 从用户输入中提取地点和时间信息
     */
    private RouteDecision.ExtractedContext extractContext(String userInput) {
        String location = extractLocation(userInput);
        String timeRange = extractTimeRange(userInput);
        String intent = extractIntent(userInput);

        Map<String, Object> additionalParams = new HashMap<>();
        if (matchesAny(userInput.toLowerCase(), FAMILY_KEYWORDS)) {
            additionalParams.put("scene", "family");
        } else if (matchesAny(userInput.toLowerCase(), COUPLE_KEYWORDS)) {
            additionalParams.put("scene", "couple");
        } else if (matchesAny(userInput.toLowerCase(), BUSINESS_KEYWORDS)) {
            additionalParams.put("scene", "business");
        }

        return RouteDecision.ExtractedContext.builder()
                .location(location)
                .timeRange(timeRange)
                .intent(intent)
                .additionalParams(additionalParams.isEmpty() ? null : additionalParams)
                .build();
    }

    /**
     * 提取地点
     */
    private String extractLocation(String text) {
        // 优先匹配城市
        Matcher cityMatcher = CITY_PATTERN.matcher(text);
        if (cityMatcher.find()) {
            return cityMatcher.group(1);
        }

        // 次级匹配省份
        Matcher provinceMatcher = PROVINCE_PATTERN.matcher(text);
        if (provinceMatcher.find()) {
            return provinceMatcher.group(1) + "省";
        }

        return null;
    }

    /**
     * 提取时间范围
     */
    private String extractTimeRange(String text) {
        for (Map.Entry<String, String> entry : TIME_KEYWORDS.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 提取用户意图
     */
    private String extractIntent(String text) {
        String lower = text.toLowerCase();
        if (matchesAny(lower, WEATHER_KEYWORDS)) return "weather_query";
        if (matchesAny(lower, FOOD_KEYWORDS)) return "food_recommendation";
        if (matchesAny(lower, TRAVEL_KEYWORDS)) return "travel_planning";
        if (matchesAny(lower, PLANNING_KEYWORDS)) return "trip_planning";
        if (matchesAny(lower, LOCATION_KEYWORDS)) return "location_query";
        return null;
    }

    // ===================== 工具方法 =====================

    /**
     * 检查输入是否包含任意一个关键字（增强版：支持分词器）
     * <p>
     * 匹配逻辑：
     * 1. 优先完整匹配（用户输入包含关键词）
     * 2. 如果启用分词器，还会进行分词后的匹配
     */
    private boolean matchesAny(String input, Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        
        String lowerInput = input.toLowerCase();
        
        // 1. 完整匹配
        for (String kw : keywords) {
            if (lowerInput.contains(kw.toLowerCase())) {
                log.trace("[KeywordRouting] 完整匹配: keyword={}", kw);
                return true;
            }
        }
        
        // 2. 分词器增强匹配
        if (tokenizerEnabled && tokenizer != null) {
            Set<String> tokens = tokenizer.tokenize(input);
            for (String kw : keywords) {
                if (tokens.contains(kw.toLowerCase())) {
                    log.trace("[KeywordRouting] 分词匹配: keyword={}, tokens={}", kw, tokens);
                    return true;
                }
            }
            // 3. ⭐ 同义词增强匹配：扩展每个 token 和 keyword
            for (String token : tokens) {
                Set<String> expandedTokens = tokenizer.expandToStandardForm(token);
                for (String kw : keywords) {
                    if (expandedTokens.contains(kw.toLowerCase())) {
                        log.trace("[KeywordRouting] 同义词匹配: token={}→{}, keyword={}", token, expandedTokens, kw);
                        return true;
                    }
                    Set<String> expandedKw = tokenizer.expandToStandardForm(kw.toLowerCase());
                    if (expandedKw.contains(token)) {
                        log.trace("[KeywordRouting] 同义词匹配: keyword={}→{}, token={}", kw, expandedKw, token);
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    private int countKeywordMatches(String input, Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        String lowerInput = input.toLowerCase();
        Set<String> tokens = tokenizerEnabled && tokenizer != null 
                ? tokenizer.tokenize(input) 
                : Collections.emptySet();
        
        for (String kw : keywords) {
            String lowerKw = kw.toLowerCase();
            // 完整匹配
            if (lowerInput.contains(lowerKw)) {
                count++;
            } 
            // 分词匹配
            else if (!tokens.isEmpty() && tokens.contains(lowerKw)) {
                count++;
            }
            // ⭐ 同义词匹配
            else if (tokenizerEnabled && tokenizer != null) {
                for (String token : tokens) {
                    Set<String> expanded = tokenizer.expandToStandardForm(token);
                    if (expanded.contains(lowerKw)) {
                        count++;
                        break;
                    }
                    Set<String> expandedKw = tokenizer.expandToStandardForm(lowerKw);
                    if (expandedKw.contains(token)) {
                        count++;
                        break;
                    }
                }
            }
        }
        
        return count;
    }
    
    /**
     * 构建路由理由文本
     */
    private String buildReason(String userInput, String agentName, Double score) {
        StringBuilder reason = new StringBuilder();
        reason.append("关键字匹配路由，");
        reason.append("Agent:").append(agentName);
        reason.append("，得分:").append(String.format("%.1f", score));

        // 补充匹配详情
        String lower = userInput.toLowerCase();
        List<String> matchedGroups = new ArrayList<>();
        if (matchesAny(lower, WEATHER_KEYWORDS)) matchedGroups.add("天气");
        if (matchesAny(lower, FOOD_KEYWORDS)) matchedGroups.add("美食");
        if (matchesAny(lower, TRAVEL_KEYWORDS)) matchedGroups.add("出行");
        if (matchesAny(lower, FAMILY_KEYWORDS)) matchedGroups.add("亲子");
        if (!matchedGroups.isEmpty()) {
            reason.append("，命中意图:").append(String.join("/", matchedGroups));
        }

        return reason.toString();
    }
}
