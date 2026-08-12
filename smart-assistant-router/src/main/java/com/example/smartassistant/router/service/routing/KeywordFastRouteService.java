package com.example.smartassistant.router.service.routing;

import com.example.smartassistant.common.intent.WeatherQuerySupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 关键词快车道服务。
 * <p>
 * 对高频明确意图（"退款"、"查订单"、"取消订单"等）跳过 LLM 分诊，
 * 直接路由到对应 Agent。优先级：经验匹配 > 关键词快车道 > 语义缓存 > LLM 意图识别。
 * </p>
 * <p>
 * 配置方式：在 {@code application.yml} 中配置 {@code router.keyword-fast-route.rules}，
 * 或通过 {@code classpath:keyword-routes.json} 外部化配置（支持热更新）。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Slf4j
@Service
public class KeywordFastRouteService {

    // ==================== 配置类 ====================

    /**
     * 关键词路由规则配置（绑定到 {@code router.keyword-fast-route}）。
     */
    @Data
    @ConfigurationProperties(prefix = "router.keyword-fast-route")
    @org.springframework.stereotype.Component
    public static class KeywordRouteProperties {
        /** 是否启用关键词快车道（默认 true） */
        private boolean enabled = true;

        /** 关键词匹配阈值：命中关键词数 / 总关键词数 >= 此值时判定为匹配（默认 0.5） */
        private double matchThreshold = 0.5;

        /** 路由规则列表（从 YAML 注入） */
        private List<KeywordRule> rules;

        /** 外部配置文件路径（classpath:keyword-routes.json 或 file:/path/to/file.json） */
        private String externalConfigPath;
    }

    /**
     * 单条关键词路由规则。
     */
    @Data
    public static class KeywordRule {
        /** 规则名称（用于日志和监控） */
        private String name;

        /** 目标 Agent 名称（product/order/general 等） */
        private String targetAgent;

        /** 意图标签（用于下游消费） */
        private String intentTag;

        /** 必含关键词列表（AND 关系，全部包含才匹配） */
        private List<String> mustContain;

        /** 任一关键词列表（OR 关系，包含一个即满足） */
        private List<String> anyContain;

        /** 排除关键词列表（包含任意一个即不匹配） */
        private List<String> exclude;

        /** 正则模式（可选，匹配整句） */
        private String regex;

        /** 置信度（匹配时返回的置信度，默认 0.95） */
        private double confidence = 0.95;

        /** 优先级（数字越小优先级越高，默认 100） */
        private int priority = 100;
    }

    // ==================== 内部类 ====================

    /**
     * 关键词匹配结果。
     */
    public static class MatchResult {
        private final String targetAgent;
        private final String intentTag;
        private final double confidence;
        private final String matchedRuleName;

        public MatchResult(String targetAgent, String intentTag, double confidence, String matchedRuleName) {
            this.targetAgent = targetAgent;
            this.intentTag = intentTag;
            this.confidence = confidence;
            this.matchedRuleName = matchedRuleName;
        }

        public String getTargetAgent() { return targetAgent; }
        public String getIntentTag() { return intentTag; }
        public double getConfidence() { return confidence; }
        public String getMatchedRuleName() { return matchedRuleName; }
    }

    // ==================== 字段 ====================

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final KeywordRouteProperties properties;
    private final List<KeywordRule> activeRules = new ArrayList<>();

    // 编译后的正则缓存（key = ruleName + regex）
    private final Map<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

    // ==================== 构造器 ====================

    public KeywordFastRouteService(KeywordRouteProperties properties) {
        this.properties = properties;
    }

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("[KeywordFastRoute] 关键词快车道已禁用");
            return;
        }

        // 1. 加载 YAML 配置的规则
        if (properties.getRules() != null && !properties.getRules().isEmpty()) {
            activeRules.addAll(properties.getRules());
            log.info("[KeywordFastRoute] 从 YAML 加载 {} 条规则", properties.getRules().size());
        }

        // 2. 尝试加载外部配置文件（JSON）
        if (properties.getExternalConfigPath() != null) {
            loadExternalConfig(properties.getExternalConfigPath());
        } else {
            // 默认尝试加载 classpath:keyword-routes.json
            loadExternalConfig("classpath:keyword-routes.json");
        }

        // 3. 如果没有任何规则，加载内置默认规则
        if (activeRules.isEmpty()) {
            loadDefaultRules();
        }

        // 4. 按优先级排序（priority 小的在前）
        activeRules.sort(Comparator.comparingInt(KeywordRule::getPriority));

        // 5. 预编译正则
        for (KeywordRule rule : activeRules) {
            if (rule.getRegex() != null && !rule.getRegex().isBlank()) {
                try {
                    compiledPatterns.put(rule.getName(), Pattern.compile(rule.getRegex()));
                } catch (PatternSyntaxException e) {
                    log.error("[KeywordFastRoute] 规则 {} 正则编译失败: {}", rule.getName(), e.getMessage());
                }
            }
        }

        log.info("[KeywordFastRoute] 初始化完成: enabled={}, rules={}, matchThreshold={}",
                properties.isEnabled(), activeRules.size(), properties.getMatchThreshold());
    }

    // ==================== 核心方法 ====================

    /**
     * 对用户输入执行关键词快车道匹配。
     *
     * @param question 用户原始问题
     * @return 匹配结果；未匹配返回 null
     */
    public MatchResult match(String question) {
        if (!properties.isEnabled() || question == null || question.isBlank()) {
            return null;
        }

        String normalized = question.toLowerCase(Locale.CHINESE);

        // ⭐ 两阶段匹配：先找第一命中，再检查是否有多意图
        MatchResult firstMatch = null;
        int firstIndex = -1;
        boolean readOnlyOrderPreparation = false;

        for (int i = 0; i < activeRules.size(); i++) {
            KeywordRule candidate = activeRules.get(i);
            if (matchesRule(normalized, candidate)) {
                if (isNegatedStateChangingRule(normalized, candidate)) {
                    readOnlyOrderPreparation |= isCreateOrderRule(candidate)
                            && isOrderPreparationRequest(normalized);
                    log.info("[KeywordFastRoute] 否定语义跳过状态变更规则: rule={}, question={}",
                            candidate.getName(), truncate(question, 50));
                    continue;
                }
                if (firstMatch == null) {
                    firstMatch = new MatchResult(
                            candidate.getTargetAgent(),
                            candidate.getIntentTag(),
                            candidate.getConfidence(),
                            candidate.getName()
                    );
                    firstIndex = i;
                } else {
                    // ⭐ 多意图检测：后续规则也命中，且指向不同 Agent
                    //    同 Agent 下多关键词（如"退款+订单号"）不视为多意图
                    KeywordRule second = activeRules.get(i);
                    if (!second.getTargetAgent().equals(activeRules.get(firstIndex).getTargetAgent())) {
                        if (isGenericProductNounOverlap(
                                activeRules.get(firstIndex), second, normalized)) {
                            continue;
                        }
                        log.info("[KeywordFastRoute] ⚠️ 多意图问题跳过快车道: "
                                        + "first={}(agent={}), second={}(agent={}), question={}",
                                activeRules.get(firstIndex).getName(),
                                activeRules.get(firstIndex).getTargetAgent(),
                                second.getName(), second.getTargetAgent(),
                                truncate(question, 50));
                        return null; // 走全管道让 LLM 处理多意图
                    }
                }
            }
        }

        if (firstMatch != null) {
            if (readOnlyOrderPreparation && "product".equalsIgnoreCase(firstMatch.targetAgent)) {
                log.info("[KeywordFastRoute] 只读下单资料说明需要商品与订单准备协作，跳过快车道: question={}",
                        truncate(question, 50));
                return null;
            }
            if (!"general".equalsIgnoreCase(firstMatch.targetAgent)
                    && WeatherQuerySupport.isWeatherLookup(question)) {
                log.info("[KeywordFastRoute] 检测到天气跨域意图，跳过单 Agent 快车道: "
                                + "firstAgent={}, question={}",
                        firstMatch.targetAgent, truncate(question, 50));
                return null;
            }
            log.info("[KeywordFastRoute] 规则命中: rule={}, agent={}, intent={}, question={}",
                    firstMatch.matchedRuleName, firstMatch.targetAgent,
                    firstMatch.intentTag, truncate(question, 50));
        }
        return firstMatch;
    }

    /**
     * “商品退货条件”中的“商品”只是退款对象，不代表额外的商品查询意图。
     * 只有同时出现价格、库存、推荐等具体商品诉求时，才保留跨 Agent 多意图判断。
     */
    private boolean isGenericProductNounOverlap(
            KeywordRule first, KeywordRule second, String normalizedQuestion) {
        boolean orderThenProduct = "order".equalsIgnoreCase(first.getTargetAgent())
                && "product".equalsIgnoreCase(second.getTargetAgent());
        if (!orderThenProduct) return false;

        return !containsAny(normalizedQuestion,
                List.of("价格", "多少钱", "库存", "有货", "有没有", "推荐", "热门", "热销",
                        "排行", "榜单", "销量", "详情", "参数", "规格", "型号"));
    }

    private static boolean containsAny(String value, List<String> markers) {
        return markers.stream().anyMatch(value::contains);
    }

    /**
     * 状态变更类快车道不能只依赖关键词命中。例如“不要创建订单”虽然包含
     * “创建订单”，真实意图却是禁止写操作。这里在所有规则（包括外部配置规则）
     * 命中后统一检查否定作用域，避免将否定约束反向解释为执行指令。
     */
    private boolean isNegatedStateChangingRule(String normalizedQuestion, KeywordRule rule) {
        List<String> actionTerms = stateChangingActionTerms(rule);
        if (actionTerms.isEmpty()) return false;

        if (containsAny(normalizedQuestion,
                List.of("只查询", "仅查询", "只做查询", "仅做查询", "只允许查询", "仅允许查询"))) {
            return true;
        }

        String compact = normalizedQuestion.replaceAll("\\s+", "");
        String negationPrefix = "(?:不是要|不要|不再|不会|无需|不用|禁止|不允许|请勿|不能|不可|不)"
                + "(?:再|直接|实际)?(?:执行|进行)?";
        return actionTerms.stream().anyMatch(term -> Pattern.compile(
                        negationPrefix + Pattern.quote(term.toLowerCase(Locale.CHINESE)))
                .matcher(compact)
                .find());
    }

    private List<String> stateChangingActionTerms(KeywordRule rule) {
        String name = Objects.toString(rule.getName(), "").toLowerCase(Locale.ROOT);
        String intent = Objects.toString(rule.getIntentTag(), "");
        if (name.contains("create_order") || intent.contains("创建订单")) {
            return List.of("下单", "购买", "买下", "创建订单");
        }
        if (name.equals("refund_fast_route") || intent.contains("退款申请")) {
            return List.of("退款", "退货", "退钱");
        }
        if (name.contains("cancel_order") || intent.contains("取消订单")) {
            return List.of("取消订单", "撤销订单", "取消");
        }
        return List.of();
    }

    private boolean isCreateOrderRule(KeywordRule rule) {
        String name = Objects.toString(rule.getName(), "").toLowerCase(Locale.ROOT);
        String intent = Objects.toString(rule.getIntentTag(), "");
        return name.contains("create_order") || intent.contains("创建订单");
    }

    private boolean isOrderPreparationRequest(String question) {
        return containsAny(question, List.of(
                "下单资料", "下单材料", "下单信息", "下单还缺", "下单需要", "下单所需",
                "说明下单", "告诉我下单", "如何下单", "怎么下单"));
    }

    /**
     * 判断问题是否匹配某条规则。
     */
    private boolean matchesRule(String normalizedQuestion, KeywordRule rule) {
        // 1. 排除关键词检查（最高优先级）
        if (rule.getExclude() != null) {
            for (String ex : rule.getExclude()) {
                if (normalizedQuestion.contains(ex.toLowerCase(Locale.CHINESE))) {
                    return false;  // 包含排除词，直接跳过此规则
                }
            }
        }

        // 2. 正则匹配（如果配置了 regex）
        if (rule.getRegex() != null && !rule.getRegex().isBlank()) {

            Pattern pattern = compiledPatterns.get(rule.getName());
            if (pattern != null && pattern.matcher(normalizedQuestion).find()) {
                return true;
            }
            // 有 regex 配置但未匹配，跳过后续关键词检查
            return false;
        }

        // 3. 必含关键词检查（AND）
        if (rule.getMustContain() != null && !rule.getMustContain().isEmpty()) {
            int matchedCount = 0;
            for (String kw : rule.getMustContain()) {
                if (normalizedQuestion.contains(kw.toLowerCase(Locale.CHINESE))) {
                    matchedCount++;
                }
            }
            double matchRatio = (double) matchedCount / rule.getMustContain().size();
            if (matchRatio < properties.getMatchThreshold()) {
                return false;
            }
        }

        // 4. 任一关键词检查（OR）
        if (rule.getAnyContain() != null && !rule.getAnyContain().isEmpty()) {
            boolean anyMatched = false;
            for (String kw : rule.getAnyContain()) {
                if (normalizedQuestion.contains(kw.toLowerCase(Locale.CHINESE))) {
                    anyMatched = true;
                    break;
                }
            }
            if (!anyMatched) {
                return false;
            }
        }

        // 5. 如果配置了 mustContain 或 anyContain，至少匹配一个才返回 true
        boolean hasKeywordConfig = (rule.getMustContain() != null && !rule.getMustContain().isEmpty())
                || (rule.getAnyContain() != null && !rule.getAnyContain().isEmpty());
        if (hasKeywordConfig) {
            return true;
        }

        // 6. 如果既没有关键词配置也没有正则配置，此规则无效
        return false;
    }

    // ==================== 配置加载 ====================

    /**
     * 从外部 JSON 文件加载规则。
     */
    private void loadExternalConfig(String location) {
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource(location);
            if (!resource.exists()) {
                log.debug("[KeywordFastRoute] 外部配置文件不存在: {}", location);
                return;
            }
            String json = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            List<KeywordRule> externalRules = parseJsonRules(json);
            if (externalRules != null && !externalRules.isEmpty()) {
                activeRules.addAll(externalRules);
                log.info("[KeywordFastRoute] 从外部文件加载 {} 条规则: {}", externalRules.size(), location);
            }
        } catch (Exception e) {
            log.warn("[KeywordFastRoute] 加载外部配置失败: {}, error={}", location, e.getMessage());
        }
    }

    /**
     * 解析 JSON 规则（Jackson 反序列化为 {@link KeywordRule} 列表）。
     * <p>支持两种形态：
     * <ul>
     *   <li>裸数组 {@code [ {rule}, {rule} ]}</li>
     *   <li>对象包裹 {@code { "rules": [ {rule}, {rule} ] }}</li>
     * </ul>
     * 字段与 {@link KeywordRule} 一一对应（name/targetAgent/intentTag/mustContain/anyContain/
     * exclude/regex/confidence/priority）。解析失败时降级为空列表，由内置默认规则兜底。
     */
    List<KeywordRule> parseJsonRules(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = JSON_MAPPER.readTree(json);
            JsonNode arr = root;
            if (root.isObject() && root.has("rules")) {
                arr = root.get("rules");
            }
            if (arr == null || !arr.isArray()) {
                log.warn("[KeywordFastRoute] JSON 规则非数组形态，跳过");
                return Collections.emptyList();
            }
            List<KeywordRule> rules = JSON_MAPPER.convertValue(
                    arr, new TypeReference<List<KeywordRule>>() {});
            log.info("[KeywordFastRoute] 解析外部 JSON 规则 {} 条", rules.size());
            return rules;
        } catch (Exception e) {
            log.warn("[KeywordFastRoute] JSON 规则解析失败（降级为默认规则）: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 加载内置默认规则（保障基础可用性）。
     */
    private void loadDefaultRules() {
        log.info("[KeywordFastRoute] 加载内置默认关键词规则");

        // 规则 1：退款/退货政策咨询（Order 模块，不需要具体订单号）
        KeywordRule refundPolicyRule = new KeywordRule();
        refundPolicyRule.setName("refund_policy_fast_route");
        refundPolicyRule.setTargetAgent("order");
        refundPolicyRule.setIntentTag("退款与售后政策");
        refundPolicyRule.setRegex(
                "(?:(?:退款|退货).{0,16}(?:条件|政策|规则|流程|要求|材料|资格|时效|期限|多久|怎么|如何|是否|能否)"
                        + "|(?:条件|政策|规则|流程|要求|材料|资格|时效|期限).{0,16}(?:退款|退货))");
        refundPolicyRule.setConfidence(0.98);
        refundPolicyRule.setPriority(9);
        activeRules.add(refundPolicyRule);

        // 规则 2：创建订单（Order 模块）。优先于商品查询规则；当同一句还包含
        // “热门/推荐/价格/库存”等明确商品诉求时，match() 会识别为跨 Agent 多意图并交给 LLM 规划。
        KeywordRule createOrderRule = new KeywordRule();
        createOrderRule.setName("create_order_fast_route");
        createOrderRule.setTargetAgent("order");
        createOrderRule.setIntentTag("创建订单");
        createOrderRule.setAnyContain(Arrays.asList("下单", "购买", "买下", "创建订单"));
        createOrderRule.setConfidence(0.97);
        createOrderRule.setPriority(9);
        activeRules.add(createOrderRule);

        // 规则 3：执行退款或查询退款进度（Order 模块）
        KeywordRule refundRule = new KeywordRule();
        refundRule.setName("refund_fast_route");
        refundRule.setTargetAgent("order");
        refundRule.setIntentTag("退款申请");
        refundRule.setAnyContain(Arrays.asList("退款", "退货", "退钱", "不要了", "不想要了"));
        refundRule.setExclude(Arrays.asList(
                "怎么退款", "如何退款", "退款流程", "退款政策", "退款条件",
                "退货条件", "退货政策", "需要满足", "哪些条件"));  // 咨询类排除
        refundRule.setConfidence(0.95);
        refundRule.setPriority(10);
        activeRules.add(refundRule);

        // 规则 4：查订单（Order 模块）
        KeywordRule queryOrderRule = new KeywordRule();
        queryOrderRule.setName("query_order_fast_route");
        queryOrderRule.setTargetAgent("order");
        queryOrderRule.setIntentTag("订单查询");
        queryOrderRule.setAnyContain(Arrays.asList("查订单", "我的订单", "订单状态", "订单号", "物流"));
        queryOrderRule.setConfidence(0.95);
        queryOrderRule.setPriority(10);
        activeRules.add(queryOrderRule);

        // 规则 5：取消订单（Order 模块）
        KeywordRule cancelRule = new KeywordRule();
        cancelRule.setName("cancel_order_fast_route");
        cancelRule.setTargetAgent("order");
        cancelRule.setIntentTag("取消订单");
        cancelRule.setAnyContain(Arrays.asList("取消订单", "撤销订单", "不要了"));
        cancelRule.setExclude(Arrays.asList("怎么取消", "如何取消"));  // 咨询类排除
        cancelRule.setConfidence(0.95);
        cancelRule.setPriority(10);
        activeRules.add(cancelRule);

        // 规则 6：商品查询（Product 模块）
        KeywordRule productRule = new KeywordRule();
        productRule.setName("product_query_fast_route");
        productRule.setTargetAgent("product");
        productRule.setIntentTag("商品查询");
        productRule.setAnyContain(Arrays.asList("商品", "产品", "价格", "多少钱", "有没有", "推荐"));
        productRule.setConfidence(0.90);
        productRule.setPriority(20);
        activeRules.add(productRule);

        // 规则 7：问候（General 模块）
        KeywordRule greetingRule = new KeywordRule();
        greetingRule.setName("greeting_fast_route");
        greetingRule.setTargetAgent("general");
        greetingRule.setIntentTag("问候");
        greetingRule.setAnyContain(Arrays.asList("你好", "您好", "hi", "hello", "在吗"));
        greetingRule.setConfidence(0.99);
        greetingRule.setPriority(5);
        activeRules.add(greetingRule);

        // 按优先级排序
        activeRules.sort(Comparator.comparingInt(KeywordRule::getPriority));
    }

    // ==================== 工具方法 ====================

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * 获取当前激活的规则数量（用于监控）。
     */
    public int getActiveRuleCount() {
        return activeRules.size();
    }

    /**
     * 重新加载规则（支持热更新）。
     */
    public void reloadRules() {
        activeRules.clear();
        compiledPatterns.clear();
        init();
    }
}
