package com.example.smartassistant.router.service.routing;

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

        for (int i = 0; i < activeRules.size(); i++) {
            if (matchesRule(normalized, activeRules.get(i))) {
                if (firstMatch == null) {
                    firstMatch = new MatchResult(
                            activeRules.get(i).getTargetAgent(),
                            activeRules.get(i).getIntentTag(),
                            activeRules.get(i).getConfidence(),
                            activeRules.get(i).getName()
                    );
                    firstIndex = i;
                } else {
                    // ⭐ 多意图检测：后续规则也命中，且指向不同 Agent
                    //    同 Agent 下多关键词（如"退款+订单号"）不视为多意图
                    KeywordRule second = activeRules.get(i);
                    if (!second.getTargetAgent().equals(activeRules.get(firstIndex).getTargetAgent())) {
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
            log.info("[KeywordFastRoute] 规则命中: rule={}, agent={}, intent={}, question={}",
                    firstMatch.matchedRuleName, firstMatch.targetAgent,
                    firstMatch.intentTag, truncate(question, 50));
        }
        return firstMatch;
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
     * 解析 JSON 规则（简单实现，生产环境建议使用 Jackson）。
     */
    private List<KeywordRule> parseJsonRules(String json) {
        // TODO: 使用 Jackson ObjectMapper 解析
        // 为简化实现，暂时返回空（依赖 YAML 配置或默认规则）
        log.debug("[KeywordFastRoute] JSON 解析暂未实现，使用 YAML 配置");
        return Collections.emptyList();
    }

    /**
     * 加载内置默认规则（保障基础可用性）。
     */
    private void loadDefaultRules() {
        log.info("[KeywordFastRoute] 加载内置默认关键词规则");

        // 规则 1：退款（Order 模块）
        KeywordRule refundRule = new KeywordRule();
        refundRule.setName("refund_fast_route");
        refundRule.setTargetAgent("order");
        refundRule.setIntentTag("退款申请");
        refundRule.setAnyContain(Arrays.asList("退款", "退货", "退钱", "不要了", "不想要了"));
        refundRule.setExclude(Arrays.asList("怎么退款", "如何退款", "退款流程", "退款政策"));  // 咨询类排除
        refundRule.setConfidence(0.95);
        refundRule.setPriority(10);
        activeRules.add(refundRule);

        // 规则 2：查订单（Order 模块）
        KeywordRule queryOrderRule = new KeywordRule();
        queryOrderRule.setName("query_order_fast_route");
        queryOrderRule.setTargetAgent("order");
        queryOrderRule.setIntentTag("订单查询");
        queryOrderRule.setAnyContain(Arrays.asList("查订单", "我的订单", "订单状态", "订单号", "物流"));
        queryOrderRule.setConfidence(0.95);
        queryOrderRule.setPriority(10);
        activeRules.add(queryOrderRule);

        // 规则 3：取消订单（Order 模块）
        KeywordRule cancelRule = new KeywordRule();
        cancelRule.setName("cancel_order_fast_route");
        cancelRule.setTargetAgent("order");
        cancelRule.setIntentTag("取消订单");
        cancelRule.setAnyContain(Arrays.asList("取消订单", "撤销订单", "不要了"));
        cancelRule.setExclude(Arrays.asList("怎么取消", "如何取消"));  // 咨询类排除
        cancelRule.setConfidence(0.95);
        cancelRule.setPriority(10);
        activeRules.add(cancelRule);

        // 规则 4：商品查询（Product 模块）
        KeywordRule productRule = new KeywordRule();
        productRule.setName("product_query_fast_route");
        productRule.setTargetAgent("product");
        productRule.setIntentTag("商品查询");
        productRule.setAnyContain(Arrays.asList("商品", "产品", "价格", "多少钱", "有没有", "推荐"));
        productRule.setConfidence(0.90);
        productRule.setPriority(20);
        activeRules.add(productRule);

        // 规则 5：问候（General 模块）
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
