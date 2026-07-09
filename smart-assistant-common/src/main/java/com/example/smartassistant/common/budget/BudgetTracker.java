package com.example.smartassistant.common.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 会话级别 + 用户级别预算追踪器。
 * <p>
 * 会话级别：追踪每轮对话的 Token 消耗、工具调用次数、Agent 轮次。
 * 用户级别：基于 Redis 统计每个用户每天的 Token 消耗和调用次数。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class BudgetTracker {

    private static final Logger log = LoggerFactory.getLogger(BudgetTracker.class);

    private static final String USER_TOKEN_KEY_PREFIX = "a2a:budget:token:";
    private static final String USER_CALL_KEY_PREFIX = "a2a:budget:call:";

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
    private final StringRedisTemplate redisTemplate;

    public BudgetTracker(BudgetConfig config, StringRedisTemplate redisTemplate) {
        this.config = config;
        this.redisTemplate = redisTemplate;
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

    /** 记录 Token 消耗（会话级 + 用户级） */
    public void recordTokens(int count) {
        if (!config.isEnabled()) return;
        SessionBudget budget = SESSION_BUDGET.get();
        budget.tokensUsed += count;
        budget.tokensThisRound += count;
    }

    /**
     * 记录 Token 消耗（含用户级配额）。
     * @param count Token 数量
     * @param userId 用户 ID（为 null 时不记录用户级配额）
     */
    public void recordTokens(int count, Long userId) {
        recordTokens(count);
        if (userId != null && config.getMaxTokensPerUserPerDay() > 0) {
            String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            String key = USER_TOKEN_KEY_PREFIX + userId + ":" + dateStr;
            redisTemplate.opsForValue().increment(key, count);
            redisTemplate.expire(key, Duration.ofDays(2)); // 过期时间 2 天
        }
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

    // ==================== 会话级检查 ====================

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

    // ==================== 用户级配额检查 ====================

    /**
     * 检查用户级别的 Token 配额是否超限。
     *
     * @param userId 用户 ID
     * @return 超限原因（null 表示未超限）
     */
    public String checkUserQuota(Long userId) {
        if (!config.isEnabled() || userId == null) return null;
        if (config.getMaxTokensPerUserPerDay() <= 0 && config.getMaxCallsPerUserPerDay() <= 0) {
            return null; // 未配置配额限制
        }

        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        // 检查 Token 配额
        if (config.getMaxTokensPerUserPerDay() > 0) {
            String tokenKey = USER_TOKEN_KEY_PREFIX + userId + ":" + dateStr;
            String current = redisTemplate.opsForValue().get(tokenKey);
            int used = current != null ? Integer.parseInt(current) : 0;
            if (used >= config.getMaxTokensPerUserPerDay()) {
                log.warn("[Budget] 用户日Token配额超限: userId={}, used={}, limit={}",
                        userId, used, config.getMaxTokensPerUserPerDay());
                return "用户今日 Token 用量已达上限（" + config.getMaxTokensPerUserPerDay()
                        + "），请明日再试或联系管理员。";
            }
        }

        // 检查调用次数配额
        if (config.getMaxCallsPerUserPerDay() > 0) {
            String callKey = USER_CALL_KEY_PREFIX + userId + ":" + dateStr;
            Long callCount = redisTemplate.opsForValue().increment(callKey);
            // increment 返回的是递增后的值
            if (callCount != null && callCount > config.getMaxCallsPerUserPerDay()) {
                log.warn("[Budget] 用户日调用次数超限: userId={}, count={}, limit={}",
                        userId, callCount, config.getMaxCallsPerUserPerDay());
                return "用户今日调用次数已达上限（" + config.getMaxCallsPerUserPerDay()
                        + "次），请明日再试或联系管理员。";
            }
            redisTemplate.expire(callKey, Duration.ofDays(2));
        }

        return null; // 未超限
    }

    // ==================== 内部类 ====================

    private static class SessionBudget {
        int tokensUsed;
        int tokensThisRound;
        int toolCalls;
        int rounds;
    }
}
