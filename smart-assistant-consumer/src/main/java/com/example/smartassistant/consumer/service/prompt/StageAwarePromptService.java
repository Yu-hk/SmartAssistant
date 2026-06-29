package com.example.smartassistant.consumer.service.prompt;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * 对话阶段感知的 Prompt 管理服务（P2 改进）。
 * <p>
 * 将静态全量 Prompt 按对话阶段拆分成聚焦指令集，降低 token 消耗，减少跨阶段上下文干扰。
 * 四阶段：GREETING（接待）→ COLLECTING（收集）→ PROCESSING（处理）→ CONFIRMING（确认）。
 * </p>
 * <p>
 * 配置方式：在 {@code application.yml} 中配置 {@code consumer.prompt.stages}。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Slf4j
@Service
public class StageAwarePromptService {

    // ==================== 枚举 ====================

    /**
     * 对话阶段。
     */
    public enum DialogStage {
        /** 接待阶段：首次接触，建立信任 */
        GREETING,

        /** 收集阶段：收集用户需求、槽位 */
        COLLECTING,

        /** 处理阶段：调用工具、查询数据、生成方案 */
        PROCESSING,

        /** 确认阶段：展示结果、等待用户确认 */
        CONFIRMING
    }

    // ==================== 配置类 ====================

    @Data
    @ConfigurationProperties(prefix = "consumer.prompt")
    @org.springframework.stereotype.Component
    public static class PromptProperties {
        /** 是否启用阶段化 Prompt（默认 true） */
        private boolean stageAwareEnabled = true;

        /** 各阶段的 Prompt 模板 */
        private Map<DialogStage, StagePromptTemplate> stages;

        /** 默认系统 Prompt（当阶段未匹配时使用） */
        private String defaultSystemPrompt;
    }

    @Data
    public static class StagePromptTemplate {
        /** 阶段名称（用于日志） */
        private String stageName;

        /** 系统指令（聚焦当前阶段） */
        private String systemInstruction;

        /** 注入的few-shot 示例（可选） */
        private List<String> fewShotExamples;

        /** 禁止操作列表（当前阶段不允许的操作） */
        private List<String> forbiddenActions;
    }

    // ==================== 字段 ====================

    private final PromptProperties properties;

    // 阶段转换规则（简化版：按对话轮次 + 意图判断）
    // 完整版应使用状态机 + 事件驱动

    // ==================== 构造器 ====================

    public StageAwarePromptService(PromptProperties properties) {
        this.properties = properties;
    }

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        if (!properties.isStageAwareEnabled()) {
            log.info("[StagePrompt] 阶段化 Prompt 已禁用，使用静态全量 Prompt");
            return;
        }

        if (properties.getStages() == null || properties.getStages().isEmpty()) {
            log.info("[StagePrompt] 未配置阶段 Prompt，加载内置默认配置");
            loadDefaultStagePrompts();
        }

        log.info("[StagePrompt] 初始化完成: enabled={}, stages={}",
                properties.isStageAwareEnabled(), properties.getStages() != null ? properties.getStages().size() : 0);
    }

    // ==================== 核心方法 ====================

    /**
     * 根据对话阶段和意图构建聚焦 Prompt。
     *
     * @param stage 当前对话阶段
     * @param intentTag 意图标签（可选，用于进一步聚焦）
     * @return 构建的 Prompt 字符串
     */
    public String buildFocusedPrompt(DialogStage stage, String intentTag) {
        if (!properties.isStageAwareEnabled() || stage == null) {
            return properties.getDefaultSystemPrompt();
        }

        StagePromptTemplate template = properties.getStages().get(stage);
        if (template == null) {
            log.warn("[StagePrompt] 未找到阶段 {} 的 Prompt 模板，使用默认 Prompt", stage);
            return properties.getDefaultSystemPrompt();
        }

        StringBuilder prompt = new StringBuilder();

        // 1. 系统指令（聚焦当前阶段）
        prompt.append(template.getSystemInstruction()).append("\n\n");

        // 2. Few-shot 示例（如果有）
        if (template.getFewShotExamples() != null && !template.getFewShotExamples().isEmpty()) {
            prompt.append("【参考示例】\n");
            for (int i = 0; i < template.getFewShotExamples().size(); i++) {
                prompt.append(i + 1).append(". ").append(template.getFewShotExamples().get(i)).append("\n");
            }
            prompt.append("\n");
        }

        // 3. 禁止操作（当前阶段）
        if (template.getForbiddenActions() != null && !template.getForbiddenActions().isEmpty()) {
            prompt.append("【当前阶段禁止操作】\n");
            for (String action : template.getForbiddenActions()) {
                prompt.append("- ").append(action).append("\n");
            }
        }

        log.debug("[StagePrompt] 构建聚焦 Prompt: stage={}, intent={}, length={}",
                stage, intentTag, prompt.length());

        return prompt.toString();
    }

    /**
     * 根据对话历史判断当前阶段（简化版）。
     *
     * @param conversationTurn 对话轮次（从 1 开始）
     * @param intentTag 意图标签
     * @param lastAgentReply 上轮 Agent 回复（用于判断是否需要确认）
     * @return 推断的对话阶段
     */
    public DialogStage inferStage(int conversationTurn, String intentTag, String lastAgentReply) {
        // 简化版阶段推断逻辑
        if (conversationTurn <= 1) {
            return DialogStage.GREETING;
        }

        if (lastAgentReply != null && (
                lastAgentReply.contains("确认") ||
                lastAgentReply.contains("请核对") ||
                lastAgentReply.contains("是否") && lastAgentReply.contains("？"))) {
            return DialogStage.CONFIRMING;
        }

        if (intentTag != null && (
                intentTag.contains("查询") ||
                intentTag.contains("推荐") ||
                intentTag.contains("分析"))) {
            return DialogStage.PROCESSING;
        }

        return DialogStage.COLLECTING;
    }

    // ==================== 默认配置 ====================

    /**
     * 加载内置默认阶段 Prompt（保障基础可用性）。
     */
    private void loadDefaultStagePrompts() {
        Map<DialogStage, StagePromptTemplate> defaultStages = new EnumMap<>(DialogStage.class);

        // GREETING 阶段
        StagePromptTemplate greeting = new StagePromptTemplate();
        greeting.setStageName("接待阶段");
        greeting.setSystemInstruction(
                "你是智能助手，正在与用户首次接触。\n" +
                "目标：建立信任，了解用户需求。\n" +
                "指令：\n" +
                "1. 友好问候，自我介绍\n" +
                "2. 引导用户描述需求\n" +
                "3. 不要轻易调用工具或查询数据"
        );
        greeting.setForbiddenActions(Arrays.asList(
                "调用订单查询工具",
                "调用商品查询工具",
                "发起退款操作"
        ));
        defaultStages.put(DialogStage.GREETING, greeting);

        // COLLECTING 阶段
        StagePromptTemplate collecting = new StagePromptTemplate();
        collecting.setStageName("收集阶段");
        collecting.setSystemInstruction(
                "你正在收集用户需求信息。\n" +
                "目标：完整收集处理请求所需的全部槽位。\n" +
                "指令：\n" +
                "1. 识别缺失的必填槽位\n" +
                "2. 一次只问一个问题\n" +
                "3. 确认信息完整性"
        );
        collecting.setFewShotExamples(Arrays.asList(
                "用户：我想退款 → 助手：请提供订单号，我帮您查询",
                "用户：推荐商品 → 助手：您对商品的预算、类型有什么偏好？"
        ));
        defaultStages.put(DialogStage.COLLECTING, collecting);

        // PROCESSING 阶段
        StagePromptTemplate processing = new StagePromptTemplate();
        processing.setStageName("处理阶段");
        processing.setSystemInstruction(
                "你正在处理用户请求。\n" +
                "目标：调用工具、查询数据、生成方案。\n" +
                "指令：\n" +
                "1. 优先使用工具获取准确数据\n" +
                "2. 数据不足时明确告知用户\n" +
                "3. 生成结构化结果"
        );
        processing.setForbiddenActions(Arrays.asList(
                "在未查询数据的情况下给出具体答案",
                "忽略工具返回的错误信息"
        ));
        defaultStages.put(DialogStage.PROCESSING, processing);

        // CONFIRMING 阶段
        StagePromptTemplate confirming = new StagePromptTemplate();
        confirming.setStageName("确认阶段");
        confirming.setSystemInstruction(
                "你正在展示处理结果，等待用户确认。\n" +
                "目标：清晰展示结果，等待用户明确确认。\n" +
                "指令：\n" +
                "1. 结构化展示结果\n" +
                "2. 明确列出待确认项\n" +
                "3. 等待用户明确回复「确认」或「取消」"
        );
        confirming.setForbiddenActions(Arrays.asList(
                "在用户未确认时执行高风险操作",
                "假设用户已确认"
        ));
        defaultStages.put(DialogStage.CONFIRMING, confirming);

        properties.setStages(defaultStages);

        // 设置默认系统 Prompt
        properties.setDefaultSystemPrompt(
                "你是 SmartAssistant，一个智能助手。请根据用户需求提供准确、有用的帮助。"
        );
    }

    // ==================== 工具方法 ====================

    /**
     * 获取当前配置的阶段数（用于监控）。
     */
    public int getConfiguredStageCount() {
        return properties.getStages() != null ? properties.getStages().size() : 0;
    }
}
