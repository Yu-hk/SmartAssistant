package com.example.smartassistant.common.agent;

/**
 * ⭐ 循环决策状态机（文章⑥ 11 条优先级链路）。
 *
 * <p>从 {@code SmartReActAgent} 提取，职责单一：根据当前循环上下文 {@link DecisionContext}
 * 及少量配置参数，按优先级（高→低）返回下一步 {@link LoopAction}。不持有 Agent 状态，
 * 纯函数式，便于单测与复用。</p>
 *
 * <p>优先级链路（命中第一条即返回，不继续检查）：</p>
 * <pre>
 *  1. FINALIZE           → 无工具调用且内容足够 → 最终输出
 *  2. ADVANCE_PHASE      → 阶段完成且有后续阶段 → 推进
 *  3. PAUSE_BLOCKED      → 循环守卫检测到阻塞  → 暂停
 *  4. PAUSE_INFRA        → 基础设施错误         → 暂停
 *  5. AWAIT_CONFIRMATION → 等待用户确认         → 暂停
 *  6. PAUSE              → 守卫请求暂停         → 暂停
 *  7. PARSE_FAILURE      → 连续解析失败≥阈值    → 暂停
 *  8. ITERATION_BUDGET   → 达到 maxIterations   → 暂停
 *  9. NO_INCREMENT       → 连续相同工具调用≥阈值 → 停止（无增量保护）
 * 10. STRATEGY_SWITCH    → 无进展≥阈值          → 换策略/终止
 * 11. CONTINUE           → 一切正常             → 继续
 * </pre>
 */
public final class AgentLoopDecision {

    /** 连续解析失败保护阈值：评估器连续返回无效结果的最多次数 */
    public static final int MAX_PARSE_FAILURES = 3;

    /** 无进展计数器阈值：连续几轮无实质进展时提前终止（= 原 MAX_NO_PROGRESS_ITERATIONS） */
    public static final int MAX_NO_PROGRESS_ITERATIONS = 3;

    /** 连续无增量检测阈值：连续几次"同工具同参数"调用时强制停止（= 原 NO_INCREMENT_LIMIT） */
    public static final int NO_INCREMENT_LIMIT = 2;

    /** 策略切换前的最大无进展轮次（与 MAX_NO_PROGRESS_ITERATIONS 对齐，使 STRATEGY_SWITCH = 无进展终止） */
    public static final int STRATEGY_SWITCH_THRESHOLD = 3;

    private AgentLoopDecision() {
    }

    /**
     * 循环决策动作（优先级从高到低）。
     * <pre>
     * FINALIZE            → 全部完成，输出最终回答
     * ADVANCE_PHASE       → 当前阶段完成，推进到下一阶段
     * PAUSE_BLOCKED       → Agent 被阻塞，暂停等待
     * PAUSE_INFRA         → 基础设施故障，暂停避免烧钱
     * AWAIT_CONFIRMATION  → 等待用户确认，暂停不替用户做决定
     * PAUSE               → 评估器/守卫请求暂停
     * PARSE_FAILURE       → 连续解析失败，暂停以避免烧预算
     * ITERATION_BUDGET    → 达到迭代上限（maxIterations），暂停
     * NO_INCREMENT        → 连续相同工具调用，停止避免调用风暴
     * STRATEGY_SWITCH     → 无进展达到阈值，换策略或委派
     * CONTINUE            → 一切正常，继续推进
     * </pre>
     */
    public enum LoopAction {
        FINALIZE, ADVANCE_PHASE, PAUSE_BLOCKED, PAUSE_INFRA,
        AWAIT_CONFIRMATION, PAUSE, PARSE_FAILURE, ITERATION_BUDGET,
        NO_INCREMENT, STRATEGY_SWITCH, CONTINUE
    }

    /**
     * 执行优先级决策链，返回下一步动作。
     *
     * @param ctx             当前决策上下文
     * @param hasPhaseChecks 是否配置了阶段检查（驱动 ADVANCE_PHASE）
     * @param maxIterations   最大迭代次数（驱动 ITERATION_BUDGET）
     * @return 优先级最高的 {@link LoopAction}
     */
    public static LoopAction decide(DecisionContext ctx, boolean hasPhaseChecks, int maxIterations) {
        // 1. FINALIZE — 无工具调用且回答内容充分
        if (ctx.noToolCalls() && ctx.hasSufficientContent()) {
            return LoopAction.FINALIZE;
        }
        // 2. ADVANCE_PHASE — 目前简化：有阶段配置且无工具调用
        if (ctx.noToolCalls() && hasPhaseChecks) {
            return LoopAction.ADVANCE_PHASE;
        }
        // 3. PAUSE_BLOCKED
        if (ctx.guardAction() == LoopGuardService.GuardAction.PAUSE_BLOCKED) {
            return LoopAction.PAUSE_BLOCKED;
        }
        // 4. PAUSE_INFRA
        if (ctx.guardAction() == LoopGuardService.GuardAction.PAUSE_INFRASTRUCTURE) {
            return LoopAction.PAUSE_INFRA;
        }
        // 5. AWAIT_CONFIRMATION
        if (ctx.guardAction() == LoopGuardService.GuardAction.AWAIT_CONFIRMATION) {
            return LoopAction.AWAIT_CONFIRMATION;
        }
        // 6. PAUSE — 守卫请求暂停（兜底）
        if (ctx.guardAction() != LoopGuardService.GuardAction.CONTINUE) {
            return LoopAction.PAUSE;
        }
        // 7. PARSE_FAILURE
        if (ctx.consecutiveParseFailures() >= MAX_PARSE_FAILURES) {
            return LoopAction.PARSE_FAILURE;
        }
        // 8. ITERATION_BUDGET
        if (ctx.iteration() >= maxIterations) {
            return LoopAction.ITERATION_BUDGET;
        }
        // 9. NO_INCREMENT — 连续相同工具调用（同名称+同参数）形成调用风暴
        if (ctx.noIncrementCount() >= NO_INCREMENT_LIMIT) {
            return LoopAction.NO_INCREMENT;
        }
        // 10. STRATEGY_SWITCH — 无进展达到阈值，换策略或委派
        if (ctx.noProgressCount() >= STRATEGY_SWITCH_THRESHOLD) {
            return LoopAction.STRATEGY_SWITCH;
        }
        // 11. CONTINUE
        return LoopAction.CONTINUE;
    }

    /** 决策状态机上下文。 */
    public record DecisionContext(
            int iteration,
            boolean noToolCalls,
            String answerText,
            LoopGuardService.GuardAction guardAction,
            int consecutiveParseFailures,
            int noProgressCount,
            int noIncrementCount) {

        boolean hasSufficientContent() {
            return !noToolCalls || (answerText != null && answerText.length() > 50);
        }
    }
}
