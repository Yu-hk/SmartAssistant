/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 循环守卫服务 — 确定性快速判断（对标文章⑥ 80% 异常代码秒判）。
 *
 * <p>在 Agent 输出后、LLM 评估前，用纯代码检测三类状态：</p>
 * <ul>
 *   <li><b>阻塞标记</b>：Agent 自称无法继续（blocked/无法继续/需要授权）</li>
 *   <li><b>用户决策请求</b>：Agent 在请求用户选择（请选择/是否继续/确认）</li>
 *   <li><b>基础设施错误</b>：Agent 报告 LLM 超时/服务错误等</li>
 * </ul>
 *
 * <p>命中任何一条即返回对应 {@link GuardResult}，无需调 LLM 评估。
 * 三条均未命中 → {@link #CONTINUE}。</p>
 */
public class LoopGuardService {

    // ═══════════════════════════════
    // 阻塞标记
    // ═══════════════════════════════
    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("(?i)(blocked|无法继续|不能继续|被阻塞|卡住|停滞|无法完成|无法处理)"),
            Pattern.compile("(?i)(需要你提供|需要您提供|需要凭据|需要授权|没有权限|权限不足)"),
            Pattern.compile("(?i)(缺少必要信息|信息不足|无法确定|不确定怎么)"),
            Pattern.compile("(?i)(请稍后再试|过一会儿再|暂时无法)")
    );

    // ═══════════════════════════════
    // 用户决策请求
    // ═══════════════════════════════
    private static final List<Pattern> USER_DECISION_PATTERNS = List.of(
            Pattern.compile("(?i)(请选择|请确认|请决定|请你选择|请您选择)"),
            Pattern.compile("(?i)(是否继续|要不要|想怎么|怎么推进|下一步怎么做)"),
            Pattern.compile("(?i)(你希望|你想让|让我知道你的决定|等你回复)"),
            Pattern.compile("(?i)(请告知|请告诉我|请指示)")
    );

    // ═══════════════════════════════
    // 基础设施错误
    // ═══════════════════════════════
    private static final List<Pattern> INFRA_ERROR_PATTERNS = List.of(
            Pattern.compile("(?i)(LLM error|model error|API error|service error)"),
            Pattern.compile("(?i)(timeout|timed out|connection refused|network error)"),
            Pattern.compile("(?i)(rate limit|quota exceeded|too many requests|429)"),
            Pattern.compile("(?i)(internal server error|500|502|503|504)"),
            Pattern.compile("(?i)(provider error|upstream error)"),
            Pattern.compile("(?i)(server_error|服务内部错误|系统繁忙)")
    );

    /** 守卫判定结果。 */
    public enum GuardAction {
        /** 一切正常，继续循环 */
        CONTINUE,
        /** Agent 被阻塞 → 暂停 */
        PAUSE_BLOCKED,
        /** Agent 在请求用户决策 → 暂停等待用户输入 */
        AWAIT_CONFIRMATION,
        /** 基础设施故障 → 暂停避免持续烧钱 */
        PAUSE_INFRASTRUCTURE
    }

    /**
     * 守卫判定结果。
     *
     * @param action    下一步动作
     * @param reason    判定理由
     * @param matched   命中的关键字（用于日志）
     */
    public record GuardResult(GuardAction action, String reason, String matched) {
        public boolean isContinue() { return action == GuardAction.CONTINUE; }
    }

    /** 命中三条规则 → CONTINUE */
    public static final GuardResult CONTINUE = new GuardResult(GuardAction.CONTINUE, "", "");

    /**
     * 执行确定性快速判断。
     *
     * @param agentOutput Agent 本轮输出的文本
     * @return {@link GuardResult}，{@link GuardAction#CONTINUE} 表示正常推进
     */
    public GuardResult analyze(String agentOutput) {
        if (agentOutput == null || agentOutput.isBlank()) {
            return CONTINUE;
        }

        // ① 基础设施错误（优先级最高：服务都挂了没必要往下走）
        for (Pattern p : INFRA_ERROR_PATTERNS) {
            var m = p.matcher(agentOutput);
            if (m.find()) {
                return new GuardResult(GuardAction.PAUSE_INFRASTRUCTURE,
                        "检测到基础设施错误: " + m.group(), m.group());
            }
        }

        // ② 阻塞标记
        for (Pattern p : BLOCKED_PATTERNS) {
            var m = p.matcher(agentOutput);
            if (m.find()) {
                return new GuardResult(GuardAction.PAUSE_BLOCKED,
                        "Agent 报告被阻塞: " + m.group(), m.group());
            }
        }

        // ③ 用户决策请求
        for (Pattern p : USER_DECISION_PATTERNS) {
            var m = p.matcher(agentOutput);
            if (m.find()) {
                return new GuardResult(GuardAction.AWAIT_CONFIRMATION,
                        "Agent 在请求用户确认: " + m.group(), m.group());
            }
        }

        return CONTINUE;
    }
}
