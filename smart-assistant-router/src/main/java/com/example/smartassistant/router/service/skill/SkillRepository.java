package com.example.smartassistant.router.service.skill;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.router.service.routing.KeywordFastRouteService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 可配置技能仓库。
 * <p>
 * 从 YAML 配置文件加载技能定义，替代硬编码的 @PostConstruct 工具注册。
 * 支持运行时热加载（通过 Nacos Config 刷新配置）。
 * </p>
 *
 * <p>技能定义文件：{@code classpath:skills/skills.yml}</p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
@RefreshScope
@ConfigurationProperties(prefix = "skill")
public class SkillRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillRepository.class);

    /** 技能定义列表（从 YAML 注入） */
    private List<SkillDefinition> skills = new ArrayList<>();

    /** 技能 ID → 定义缓存 */
    private final Map<String, SkillDefinition> skillMap = new ConcurrentHashMap<>();

    /** 触发词 → 技能列表（用于快速匹配） */
    private final Map<String, List<SkillDefinition>> triggerIndex = new ConcurrentHashMap<>();

    private final ToolRegistry toolRegistry;
    private final KeywordFastRouteService.KeywordRouteProperties keywordRouteProperties;

    public SkillRepository(ToolRegistry toolRegistry,
                           KeywordFastRouteService.KeywordRouteProperties keywordRouteProperties) {
        this.toolRegistry = toolRegistry;
        this.keywordRouteProperties = keywordRouteProperties;
    }

    public List<SkillDefinition> getSkills() { return skills; }
    public void setSkills(List<SkillDefinition> skills) { this.skills = skills; }

    @PostConstruct
    public void init() {
        rebuildIndex();
    }

    /**
     * 重建技能索引（在配置刷新时调用）。
     */
    public void rebuildIndex() {
        skillMap.clear();
        triggerIndex.clear();

        // 加载默认技能（如果配置为空）
        if (skills == null || skills.isEmpty()) {
            log.info("[SkillRepo] 未配置技能，加载内置默认技能");
            skills = loadDefaultSkills();
        }

        // 建索引
        for (SkillDefinition skill : skills) {
            if (!skill.enabled()) continue;
            skillMap.put(skill.id(), skill);

            // 触发词索引
            if (skill.triggers() != null) {
                for (String trigger : skill.triggers()) {
                    triggerIndex.computeIfAbsent(trigger.toLowerCase(), k -> new ArrayList<>()).add(skill);
                }
            }

            // 自动注册到 ToolRegistry
            ToolRiskLevel risk = switch (skill.riskLevel() != null ? skill.riskLevel().toUpperCase() : "LOW") {
                case "HIGH" -> ToolRiskLevel.HIGH;
                case "MEDIUM" -> ToolRiskLevel.MEDIUM;
                default -> ToolRiskLevel.READ;
            };
            toolRegistry.register(ToolDefinition.builder()
                    .name(skill.id())
                    .description(skill.name())
                    .riskLevel(risk)
                    .timeout(Duration.ofSeconds(15))
                    .needsApproval(risk == ToolRiskLevel.HIGH)
                    .maxRetries(1)
                    .rateLimit(skill.isHighRisk() ? 10 : 0)
                    .build()
            );
        }

        int enabledCount = (int) skills.stream().filter(SkillDefinition::enabled).count();
        log.info("[SkillRepo] 加载完成: total={}, enabled={}, triggers={}",
                skills.size(), enabledCount, triggerIndex.size());
    }

    /**
     * 根据技能 ID 获取定义。
     */
    public SkillDefinition get(String id) {
        return skillMap.get(id);
    }

    /**
     * 获取所有启用的技能。
     */
    public List<SkillDefinition> getEnabledSkills() {
        return skills.stream().filter(SkillDefinition::enabled).collect(Collectors.toList());
    }

    /**
     * 根据触发词匹配技能。
     */
    public List<SkillDefinition> matchByTrigger(String question) {
        if (question == null || question.isBlank()) return List.of();
        String q = question.toLowerCase();

        Set<SkillDefinition> matched = new LinkedHashSet<>();
        for (Map.Entry<String, List<SkillDefinition>> entry : triggerIndex.entrySet()) {
            if (q.contains(entry.getKey())) {
                matched.addAll(entry.getValue());
            }
        }

        // 正则匹配
        for (SkillDefinition skill : skills) {
            if (!skill.enabled()) continue;
            if (skill.patterns() != null) {
                for (String pattern : skill.patterns()) {
                    try {
                        if (question.matches(pattern)) {
                            matched.add(skill);
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("[SkillRepo] 技能匹配正则失败: pattern={}, error={}", pattern, e.getMessage());
                    }
                }
            }
        }

        return matched.stream()
                .sorted(Comparator.comparingInt(SkillDefinition::priority))
                .collect(Collectors.toList());
    }

    /**
     * 获取技能示例列表（用于小模型分类器）。
     * 返回 Map<skillId, List<example>>
     */
    public Map<String, List<String>> getExamplesMap() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (SkillDefinition skill : skills) {
            if (!skill.enabled() || skill.examples() == null || skill.examples().isEmpty()) continue;
            result.put(skill.id(), skill.examples());
        }
        return result;
    }

    /** 技能总数 */
    public int size() { return skills.size(); }

    // ==================== 内置默认技能 ====================

    private List<SkillDefinition> loadDefaultSkills() {
        return List.of(
                // Order 技能
                new SkillDefinition("order_query", "订单查询", "查询订单状态和详细信息",
                        "order", "order", "订单查询", 10,
                        List.of("查订单", "订单状态", "我的订单", "物流"),
                        List.of(), List.of("查一下我的订单", "订单号是什么", "物流信息"),
                        Map.of("orderId", new SkillDefinition.SkillSlot("orderId", "string", false, null, "请问订单号是什么？")),
                        "LOW", true),

                new SkillDefinition("order_refund", "退款申请", "提交退款申请",
                        "order", "order", "退款申请", 10,
                        List.of("退款", "退货", "退钱"),
                        List.of(), List.of("我要退款", "申请退货", "退钱"),
                        Map.of("orderId", new SkillDefinition.SkillSlot("orderId", "string", true, null, "请问需要退哪个订单？"),
                               "reason", new SkillDefinition.SkillSlot("reason", "string", false, null, "请问退款原因是什么？")),
                        "HIGH", true),

                new SkillDefinition("order_cancel", "取消订单", "取消指定订单",
                        "order", "order", "取消订单", 10,
                        List.of("取消订单", "撤销订单", "不买了"),
                        List.of(), List.of("取消订单", "不想要了"),
                        Map.of("orderId", new SkillDefinition.SkillSlot("orderId", "string", true, null, "请问要取消哪个订单？")),
                        "HIGH", true),

                // Product 技能
                new SkillDefinition("product_query", "商品查询", "查询商品详细信息和价格",
                        "product", "product", "商品查询", 20,
                        List.of("查商品", "价格", "多少钱", "有没有"),
                        List.of(), List.of("这个商品多少钱", "有没有 iPhone", "推荐手机"),
                        Map.of("keyword", new SkillDefinition.SkillSlot("keyword", "string", false, null, "请问想查什么商品？")),
                        "LOW", true),

                new SkillDefinition("stock_check", "库存查询", "查询商品库存状态",
                        "product", "product", "库存查询", 20,
                        List.of("库存", "有没有货", "现货"),
                        List.of(), List.of("有货吗", "库存充足吗"),
                        null, "LOW", true),

                // General 技能
                new SkillDefinition("greeting", "问候", "打招呼和闲聊",
                        "general", "general", "问候", 5,
                        List.of("你好", "您好", "在吗", "hi", "hello"),
                        List.of(), List.of("你好", "在吗"),
                        null, "LOW", true),

                new SkillDefinition("weather_query", "天气查询", "查询城市天气预报",
                        "general", "general", "天气查询", 20,
                        List.of("天气", "下雨", "气温", "多少度"),
                        List.of(), List.of("今天天气怎么样", "北京下雨吗"),
                        Map.of("city", new SkillDefinition.SkillSlot("city", "string", true, null, "请问要查询哪个城市的天气？")),
                        "LOW", true),

                new SkillDefinition("hot_news", "新闻热点", "查询当前热点新闻",
                        "general", "general", "新闻热点", 25,
                        List.of("新闻", "热点", "热搜", "最新消息"),
                        List.of(), List.of("今天有什么新闻", "热点话题"),
                        null, "LOW", true),

                new SkillDefinition("calculate", "数学计算", "数学表达式计算",
                        "general", "general", "数学计算", 25,
                        List.of("计算", "等于", "加减乘除", "算一下"),
                        List.of(), List.of("计算 2+3", "15*4等于多少"),
                        Map.of("expression", new SkillDefinition.SkillSlot("expression", "string", true, null, "请输入要计算的表达式")),
                        "LOW", true)
        );
    }
}
