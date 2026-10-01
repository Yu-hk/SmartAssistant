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
 *   <li><b>阻塞标记</b>：Agent 报告当前执行受阻或缺少操作凭据；资料不足不是执行阻塞</li>
 *   <li><b>用户决策请求</b>：Agent 在请求用户选择（请选择/是否继续/确认）</li>
 *   <li><b>基础设施错误</b>：Agent 报告 LLM 超时/服务错误等</li>
 * </ul>
 *
 * <p>命中任何一条即返回对应 {@link GuardResult}，无需调 LLM 评估。
 * 三条均未命中 → {@link #CONTINUE}。</p>
 */
public class LoopGuardService {

    // ═══════════════════════════════
    // 阻塞标记：只识别当前执行状态，不能把“暂时无法确定 / 信息不足”这类
    // 忠实的知识边界答复改写成系统错误。按句首/当前执行主体限定，而不是为整段
    // 包含知识不足的回答开白名单；后续句子里的真实权限或工具阻塞仍必须拦截。
    // ═══════════════════════════════
    private static final String STATUS_PREFIX = "(?i)(?:^|[。！？!?；;\\r\\n])\\s*"
            + "(?:(?:很?抱歉|另外|同时)[，,：:]\\s*)?(?:(?:目前|现在)\\s*)?";
    private static final String EXECUTION_SUBJECT = "(?:(?:我|我们|Agent|代理)(?:当前|目前|现在|暂时)?\\s*"
            + "|(?:当前|本次)(?:账号|用户|请求|任务|执行|数据库|工具|操作)?(?:的)?(?:访问)?\\s*)?";
    private static final String CREDENTIAL = "(?:(?:访问|登录|数据库|管理员)(?:的)?)?"
            + "(?:凭据|授权|权限|密码|密钥|令牌|API\\s*Key)";
    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            // Bare execution status or explicit agent/task status, not "blocked stock" in documentation.
            Pattern.compile(STATUS_PREFIX + EXECUTION_SUBJECT
                    + "(?:(?:任务|请求|执行|操作|流程)\\s*)?"
                    + "(?:blocked|无法继续|不能继续|被阻塞|卡住|停滞)(?:了)?"
                    + "\\s*(?=[，,。！？.!?；;]|$)"),
            Pattern.compile(STATUS_PREFIX + "(?:\\[blocked\\]|\\b(?:I|we|agent|task|execution|tool)\\s+"
                    + "(?:(?:am|are|is|has been)\\s+)?(?:(?:currently|now)\\s+)?blocked\\b)"),
            Pattern.compile(STATUS_PREFIX + "(?:当前|本次)?(?:任务|请求|执行|操作|流程|Agent|代理)"
                    + "(?:由于|因为|因)[^。！？!?；;\\r\\n]{0,24}(?:被阻塞|卡住|停滞|无法继续|不能继续)"),
            // Requests for documents, addresses or product names are business follow-up, not credentials.
            Pattern.compile(STATUS_PREFIX + EXECUTION_SUBJECT + "(?<!不)(?:需要|缺少|缺乏|未提供|未配置|没有)"
                    + "(?:有效的?|必要的?)?" + CREDENTIAL),
            Pattern.compile(STATUS_PREFIX + EXECUTION_SUBJECT + "(?:(?:由于|因为|因)\\s*)?"
                    + "(?:(?:数据库|工具|接口|API)(?:访问)?)?(?:没有权限|权限不足)"),
            Pattern.compile(STATUS_PREFIX + EXECUTION_SUBJECT + "需要(?:你|您)提供"
                    + "[^。！？!?；;\\r\\n]{0,20}" + CREDENTIAL),
            // Operational verbs/targets distinguish inability to execute from inability to establish a fact.
            Pattern.compile(STATUS_PREFIX + EXECUTION_SUBJECT + "(?:暂时)?(?:无法|不能)"
                    + "[^。！？!?；;\\r\\n]{0,8}(?:执行|调用|连接|登录|访问|继续(?:执行|操作|处理|办理|查询))"),
            Pattern.compile(STATUS_PREFIX + EXECUTION_SUBJECT + "(?:暂时)?(?:无法|不能)"
                    + "(?:处理|完成)(?:当前|本次|该)?(?:请求|任务|操作|流程)"),
            Pattern.compile(STATUS_PREFIX + "(?:当前|所需|必要的?|查询|检索)?(?:工具|数据库|API|接口|服务)"
                    + "[^。！？!?；;\\r\\n]{0,16}(?:未配置|未授权|不可用|无法使用|无法访问)")
    );

    // ═══════════════════════════════
    // 用户决策请求
    // ═══════════════════════════════
    private static final List<Pattern> USER_DECISION_PATTERNS = List.of(
            Pattern.compile("(?i)(请选择|请决定|请你选择|请您选择)"),
            Pattern.compile("(?i)请确认.{0,16}(是否|继续|执行|提交|授权|订单|支付|退款|取消|购买|下单|删除|修改)"),
            Pattern.compile("(?i)(继续|执行|提交|授权|订单|支付|退款|取消|购买|下单|删除|修改).{0,16}请确认"),
            Pattern.compile("(?i)(是否继续|要不要|想怎么|怎么推进|下一步怎么做)"),
            Pattern.compile("(?i)(你希望|你想让|让我知道你的决定|等你回复)"),
            Pattern.compile("(?i)请(告知|告诉我|指示).{0,20}(选择|决定|是否|继续|执行|提交|授权|支付|退款|取消|购买|下单|删除|修改)")
    );

    // ═══════════════════════════════
    // 基础设施错误
    // ═══════════════════════════════
    private static final List<Pattern> INFRA_ERROR_PATTERNS = List.of(
            Pattern.compile("(?i)(LLM error|model error|API error|service error)"),
            Pattern.compile("(?i)(timeout|timed out|connection refused|network error)"),
            Pattern.compile("(?i)(rate limit|quota exceeded|too many requests)"),
            Pattern.compile("(?i)(internal server error|(?:HTTP(?:/\\d(?:\\.\\d)?)?|status(?: code)?|状态码|错误码)\\s*[:：=]?\\s*(?:429|500|502|503|504)\\b)"),
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
