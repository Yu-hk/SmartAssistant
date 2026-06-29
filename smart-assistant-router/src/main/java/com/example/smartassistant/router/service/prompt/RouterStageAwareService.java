package com.example.smartassistant.router.service.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * P2 路由级对话阶段感知 Prompt 服务。
 * <p>
 * 根据对话阶段（GREETING/COLLECTING/PROCESSING/CONFIRMING）
 * 动态选择聚焦的 System Prompt，替代全量静态注入。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class RouterStageAwareService {

    private static final Logger log = LoggerFactory.getLogger(RouterStageAwareService.class);

    // ==================== 对话阶段 ====================

    public enum DialogStage {
        /** 接待阶段：首次接触，建立信任 */
        GREETING,
        /** 收集阶段：收集用户需求 */
        COLLECTING,
        /** 处理阶段：执行查询/推荐 */
        PROCESSING,
        /** 确认阶段：展示结果/等待确认 */
        CONFIRMING
    }

    // ==================== 阶段感知 Prompt 构建 ====================

    /**
     * 根据对话阶段选择合适的 System Prompt 前缀。
     * <p>
     * 注入方式：在原始 System Prompt 前或后追加阶段指令。
     * </p>
     */
    public String getStageDirective(DialogStage stage) {
        if (stage == null) return "";

        return switch (stage) {
            case GREETING ->
                """
                ## 📍 当前阶段：接待
                目标：建立信任，了解用户需求。
                指令：
                - 友好问候，简要自我介绍
                - 引导用户描述需求
                - 不要轻易调用查询工具
                """;

            case COLLECTING ->
                """
                ## 📍 当前阶段：需求收集
                目标：完整收集处理请求所需的全部信息。
                指令：
                - 一次只问一个问题
                - 确认信息完整性
                - 识别缺失的必填信息
                """;

            case PROCESSING ->
                """
                ## 📍 当前阶段：处理
                目标：执行查询、调用工具、生成方案。
                指令：
                - 优先使用工具获取准确数据
                - 数据不足时明确告知用户
                - 生成结构化结果
                """;

            case CONFIRMING ->
                """
                ## 📍 当前阶段：确认
                目标：展示处理结果，等待用户确认。
                指令：
                - 结构化展示结果
                - 明确列出待确认项
                - 不要在用户未确认时执行高风险操作
                """;
        };
    }

    /**
     * 根据对话轮次和意图推断当前阶段。
     */
    public DialogStage inferStage(int turnCount, String intentTag, String lastReply) {
        if (turnCount <= 1 || intentTag == null) {
            return DialogStage.GREETING;
        }

        // 包含确认关键词的回复 → CONFIRMING
        if (lastReply != null && (
                lastReply.contains("确认") || lastReply.contains("请核对") ||
                lastReply.contains("是否") && lastReply.contains("？") ||
                lastReply.contains("对吗") || lastReply.contains("可以吗"))) {
            return DialogStage.CONFIRMING;
        }

        // 查询/推荐类意图 → PROCESSING
        if (intentTag.contains("查询") || intentTag.contains("推荐") ||
                intentTag.contains("分析") || intentTag.contains("搜索") ||
                intentTag.contains("计算")) {
            return DialogStage.PROCESSING;
        }

        // 其他 → COLLECTING
        return DialogStage.COLLECTING;
    }

    /**
     * 将阶段指令包装到原始 System Prompt 中。
     */
    public String wrapPrompt(String originalSystemPrompt, DialogStage stage) {
        String directive = getStageDirective(stage);
        if (directive.isBlank()) {
            return originalSystemPrompt;
        }
        return originalSystemPrompt + "\n\n" + directive;
    }
}
