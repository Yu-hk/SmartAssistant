package com.example.smartassistant.common.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话级别预算追踪器。
 * <p>
 * 追踪每轮对话的 Token 消耗、工具调用次数、Agent 轮次，
 * 超限时提供降级信号供调用方处理。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class BudgetTracker {

    private static final Logger log = LoggerFactory.getLogger(BudgetTracker.class);

    /** 预算检查结果 */
    public record BudgetStatus(
            boolean exceeded,
            String reason,
            int tokensUsed,
            int toolCalls,
            int rounds
    ) {
        public static BudgetStatus ok(int tokens, int tools, int rounds) {
            return new BudgetStatus(false, null, tokens, tools, rounds);
        }
        public static BudgetStatus over(String reason, int tokens, int tools, int rounds) {
            return new BudgetStatus(true, reason, tokens, tools, rounds);
        }
    }

    /** 客户端请求上下文（ThreadLocal 避免污染其他请求） */
    private static final ThreadLocal<SessionBudget> SESSION_BUDGET = ThreadLocal.withInitial(SessionBudget::new);

    private final BudgetConfig config;

    public BudgetTracker(BudgetConfig config) {
        this.config = config;
    }

    // ==================== 生命周期 ====================

    /** 开始新会话的预算追踪 */
    public void startSession() {
        SESSION_BUDGET.set(new SessionBudget());
        log.debug("[Budget] 新会话预算开始");
    }

    /** 结束会话并清理 */
    public void endSession() {
        SessionBudget budget = SESSION_BUDGET.get();
        if (budget != null && budget.tokensUsed > 0) {
            log.info("[Budget] 会话结束: tokens={}, toolCalls={}, rounds={}",
                    budget.tokensUsed, budget.toolCalls, budget.rounds);
        }
        SESSION_BUDGET.remove();
    }

    // ==================== 记录消耗 ====================

    /** 记录 Token 消耗 */
    public void recordTokens(int count) {
        if (!config.isEnabled()) return;
        SessionBudget budget = SESSION_BUDGET.get();
        budget.tokensUsed += count;
        budget.tokensThisRound += count;
    }

    /** 记录工具调用 */
    public void recordToolCall() {
        if (!config.isEnabled()) return;
        SESSION_BUDGET.get().toolCalls++;
    }

    /** 记录 Agent 轮次 */
    public void recordRound() {
        if (!config.isEnabled()) return;
        SESSION_BUDGET.get().rounds++;
    }

    /** 标记新轮次（重置轮次内 Token 计数） */
    public void newRound() {
        if (!config.isEnabled()) return;
        SESSION_BUDGET.get().tokensThisRound = 0;
    }

    // ==================== 检查 ====================

    /** 检查会话总预算是否超限 */
    public BudgetStatus checkSession() {
        if (!config.isEnabled()) return BudgetStatus.ok(0, 0, 0);

        SessionBudget budget = SESSION_BUDGET.get();

        if (budget.tokensUsed >= config.getMaxTokensPerSession()) {
            return BudgetStatus.over(
                    "会话Token超限: " + budget.tokensUsed + "/" + config.getMaxTokensPerSession(),
                    budget.tokensUsed, budget.toolCalls, budget.rounds);
        }
        if (budget.toolCalls >= config.getMaxToolCallsPerSession()) {
            return BudgetStatus.over(
                    "工具调用超限: " + budget.toolCalls + "/" + config.getMaxToolCallsPerSession(),
                    budget.tokensUsed, budget.toolCalls, budget.rounds);
        }
        if (budget.rounds >= config.getMaxRoundsPerSession()) {
            return BudgetStatus.over(
                    "Agent轮次超限: " + budget.rounds + "/" + config.getMaxRoundsPerSession(),
                    budget.tokensUsed, budget.toolCalls, budget.rounds);
        }

        return BudgetStatus.ok(budget.tokensUsed, budget.toolCalls, budget.rounds);
    }

    /** 检查本轮 Token 是否超限 */
    public boolean isThisRoundOverLimit() {
        if (!config.isEnabled()) return false;
        return SESSION_BUDGET.get().tokensThisRound >= config.getMaxTokensPerCall();
    }

    /** 获取降级行为 */
    public String getOnExceededAction() {
        return config.getOnExceeded();
    }

    // ==================== 内部类 ====================

    private static class SessionBudget {
        int tokensUsed;
        int tokensThisRound;
        int toolCalls;
        int rounds;
    }
}
