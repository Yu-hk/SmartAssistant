package com.example.smartassistant.router.service.skill;

import java.util.List;
import java.util.Map;

/**
 * 技能定义（可配置，支持 YAML/JSON 加载）。
 * <p>
 * 替代硬编码的 @PostConstruct 工具注册，技能可通过配置文件定义，
 * 支持 Nacos Config 运行时热加载。
 * </p>
 *
 * @param id          技能唯一标识（如 order_query）
 * @param name        技能名称（如"订单查询"）
 * @param description 技能描述
 * @param category    分类（order/product/general）
 * @param routeTo     路由到的 Agent 名称
 * @param intentTag   意图标签
 * @param priority    优先级（数字越小优先级越高）
 * @param triggers    触发词列表（用于关键词快车道）
 * @param patterns    正则模式列表（用于规则匹配）
 * @param examples    示例问题（用于小模型分类器训练中心向量）
 * @param slots       需要抽取的槽位
 * @param riskLevel   风险等级（LOW/MEDIUM/HIGH）
 * @param enabled     是否启用
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
public record SkillDefinition(
        String id,
        String name,
        String description,
        String category,
        String routeTo,
        String intentTag,
        int priority,
        List<String> triggers,
        List<String> patterns,
        List<String> examples,
        Map<String, SkillSlot> slots,
        String riskLevel,
        boolean enabled
) {
    public record SkillSlot(
            String name,
            String type,
            boolean required,
            String defaultValue,
            String askPrompt
    ) {}

    /** 是否是高风险技能 */
    public boolean isHighRisk() {
        return "HIGH".equalsIgnoreCase(riskLevel);
    }

    /** 是否命中触发词 */
    public boolean matchesTrigger(String question) {
        if (!enabled || question == null) return false;
        String q = question.toLowerCase();
        if (triggers != null) {
            for (String t : triggers) {
                if (q.contains(t.toLowerCase())) return true;
            }
        }
        if (patterns != null) {
            for (String p : patterns) {
                if (question.matches(p)) return true;
            }
        }
        return false;
    }
}
