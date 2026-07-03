/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ⭐ Token Budget Reminder + Prompt 注入防御。
 * <p>
 * <b>Token Budget Reminder</b>：临近阈值时注入提醒，每个窗口只发一次。
 * Agent 可据此决定：收尾当前步骤、简化后续操作、或主动请求压缩。
 * </p>
 * <p>
 * <b>Prompt 注入防御</b>：在文档内容进入上下文前检测注入指令模式，
 * 参考 RAG 文章"安全风险"章节。
 * </p>
 */
public class AgentSafetyService {

    private static final Logger log = LoggerFactory.getLogger(AgentSafetyService.class);

    // ═══════════════════════════════════════════════════════════
    // Token Budget Reminder
    // ═══════════════════════════════════════════════════════════

    /** Token Budget Reminder 已发送标记（每个窗口只发一次） */
    private boolean reminderSent = false;

    /** Token 阈值：达到窗口的 75% 时发送提醒 */
    private static final double BUDGET_REMINDER_RATIO = 0.75;

    /** Token Budget Reminder 消息 */
    private static final String BUDGET_REMINDER_MESSAGE =
            "【Token 预算提醒】当前上下文已使用约 %.0f%% 的预算。"
                    + "请考虑收尾当前步骤，简化后续操作，或主动请求压缩以避免上下文溢出。";

    /**
     * 检查是否需要发送 Token Budget Reminder。
     *
     * @param currentTokens 当前 Token 数
     * @param maxTokens     最大 Token 预算
     * @return 提醒消息（不需要时返回 null）
     */
    public String checkBudgetReminder(long currentTokens, long maxTokens) {
        if (reminderSent || maxTokens <= 0) return null;

        double ratio = (double) currentTokens / maxTokens;
        if (ratio >= BUDGET_REMINDER_RATIO) {
            reminderSent = true;
            String message = String.format(BUDGET_REMINDER_MESSAGE, ratio * 100);
            log.info("[AgentSafety] Token 预算提醒: {}/{} ({:.1f}%)", currentTokens, maxTokens, ratio * 100);
            return message;
        }
        return null;
    }

    /** 重置提醒标记（窗口重置时调用） */
    public void resetReminder() {
        this.reminderSent = false;
    }

    // ═══════════════════════════════════════════════════════════
    // Prompt 注入防御
    // ═══════════════════════════════════════════════════════════

    /** 注入指令模式 — 检测文档内容中的越权指令 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // 忽略规则指令
            Pattern.compile("忽略(上述|以上|所有|前面)?(规则|指令|限制|约束|要求)", Pattern.CASE_INSENSITIVE),
            // 重写系统提示
            Pattern.compile("重写(系统|初始|你的)?(提示|指令|prompt|role)", Pattern.CASE_INSENSITIVE),
            // 泄露系统提示
            Pattern.compile("(泄露|输出|出示).{0,20}(系统|初始|你的).{0,10}(提示|指令|prompt)", Pattern.CASE_INSENSITIVE),
            // 假装已完成
            Pattern.compile("(假装|假设).{0,10}(已经|已).{0,10}(完成|处理|执行)", Pattern.CASE_INSENSITIVE),
            // 越权指令
            Pattern.compile("请(忘记|忽略|删除|覆盖).{0,10}(所有|之前|以上).{0,10}(规则|指令|约束)", Pattern.CASE_INSENSITIVE)
    );

    /** 注入指令关键词快速扫描（优先于正则，更高效） */
    private static final Set<String> INJECTION_KEYWORDS = Set.of(
            "忽略规则", "忽略指令", "忽略约束",
            "重写提示", "重写prompt",
            "假装完成", "假装处理",
            "忘记规则", "忘记指令",
            "泄露系统提示",
            "你是客服", "你是一个"  // 尝试覆盖系统角色
    );

    /**
     * 检测文本是否包含 Prompt 注入指令。
     *
     * @param text 待检查的文本（文档内容、检索结果等）
     * @return 注入检测结果
     */
    public InjectionResult detectInjection(String text) {
        if (text == null || text.isBlank()) {
            return InjectionResult.safe();
        }

        // Step 1: 关键词快速检测
        for (String keyword : INJECTION_KEYWORDS) {
            if (text.contains(keyword)) {
                log.warn("[AgentSafety] ⚠️ 检测到注入关键词: '{}'", keyword);
                return InjectionResult.blocked("检测到注入关键词: '" + keyword + "'");
            }
        }

        // Step 2: 正则深度检测
        for (Pattern pattern : INJECTION_PATTERNS) {
            var matcher = pattern.matcher(text);
            if (matcher.find()) {
                String matched = matcher.group();
                log.warn("[AgentSafety] ⚠️ 检测到注入模式: '{}'", matched);
                return InjectionResult.blocked("检测到注入模式: '" + matched + "'");
            }
        }

        return InjectionResult.safe();
    }

    /**
     * 批量检测文档内容（用于知识库检索结果）。
     *
     * @param texts 多个文本片段
     * @return 安全的文本片段列表（排除恶意内容）
     */
    public List<String> filterSafe(List<String> texts) {
        return texts.stream()
                .filter(t -> detectInjection(t).isSafe())
                .toList();
    }

    /** 注入检测结果 */
    public record InjectionResult(boolean isSafe, String reason) {
        public static InjectionResult safe() { return new InjectionResult(true, null); }
        public static InjectionResult blocked(String reason) { return new InjectionResult(false, reason); }
    }
}
