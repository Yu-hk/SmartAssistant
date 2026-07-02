package com.example.smartassistant.router.service.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * P2 新增指标的 Prometheus 采集器。
 * <p>
 * 覆盖 L3 意图融合、L5 意图漂移、预算追踪、ToolGateway 等新服务的指标。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Component
public class NewMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(NewMetricsCollector.class);

    // ==================== L3 意图融合 ====================

    private final Counter intentFusionTotal;
    private final Counter intentFusionRule;
    private final Counter intentFusionClassifier;
    private final Counter intentFusionLLM;
    private final Counter intentFusionFallback;

    // ==================== L5 意图漂移 ====================

    private final Counter driftDetected;
    private final Counter driftStrong;

    // ==================== 预算追踪 ====================

    private final Counter budgetExceeded;

    // ==================== ToolGateway ====================

    private final Counter toolExecTotal;
    private final Counter toolExecSuccess;
    private final Counter toolExecFailed;
    private final Counter toolExecRetryable;
    private final Counter toolExecFatal;
    private final Counter toolCircuitBreakerOpen;

    /** ⭐ 工具执行延迟分位数 Timer（p50/p95/p99） */
    private final Timer toolExecLatency;

    public NewMetricsCollector(MeterRegistry meterRegistry) {
        // L3 融合
        this.intentFusionTotal = Counter.builder("a2a_intent_fusion_total")
                .description("Total intent fusion calls")
                .register(meterRegistry);
        this.intentFusionRule = Counter.builder("a2a_intent_fusion_source")
                .description("Intent fusion by source")
                .tag("source", "rule")
                .register(meterRegistry);
        this.intentFusionClassifier = Counter.builder("a2a_intent_fusion_source")
                .description("Intent fusion by source")
                .tag("source", "classifier")
                .register(meterRegistry);
        this.intentFusionLLM = Counter.builder("a2a_intent_fusion_source")
                .description("Intent fusion by source")
                .tag("source", "llm")
                .register(meterRegistry);
        this.intentFusionFallback = Counter.builder("a2a_intent_fusion_source")
                .description("Intent fusion by source")
                .tag("source", "fallback")
                .register(meterRegistry);

        // L5 漂移
        this.driftDetected = Counter.builder("a2a_intent_drift_total")
                .description("Intent drift detected")
                .tag("level", "drift")
                .register(meterRegistry);
        this.driftStrong = Counter.builder("a2a_intent_drift_total")
                .description("Strong intent drift detected")
                .tag("level", "strong")
                .register(meterRegistry);

        // 预算
        this.budgetExceeded = Counter.builder("a2a_budget_exceeded_total")
                .description("Budget exceeded events")
                .tag("type", "session")
                .register(meterRegistry);

        // ToolGateway
        this.toolExecTotal = Counter.builder("a2a_tool_execution_total")
                .description("Total tool executions")
                .register(meterRegistry);
        this.toolExecSuccess = Counter.builder("a2a_tool_execution_result")
                .description("Tool execution results")
                .tag("status", "success")
                .register(meterRegistry);
        this.toolExecFailed = Counter.builder("a2a_tool_execution_result")
                .description("Tool execution results")
                .tag("status", "failed")
                .register(meterRegistry);
        this.toolExecRetryable = Counter.builder("a2a_tool_execution_result")
                .description("Tool execution error type")
                .tag("status", "retryable_failed")
                .register(meterRegistry);
        this.toolExecFatal = Counter.builder("a2a_tool_execution_result")
                .description("Tool execution error type")
                .tag("status", "fatal_failed")
                .register(meterRegistry);
        this.toolCircuitBreakerOpen = Counter.builder("a2a_tool_circuit_breaker")
                .description("Tool circuit breaker opened")
                .register(meterRegistry);

        // ⭐ 工具执行延迟分位数 Timer
        this.toolExecLatency = Timer.builder("a2a_tool_execution_latency_percentile")
                .description("Tool execution latency with p50/p95/p99")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        log.info("[Metrics] 新指标采集器初始化完成: 4类12指标 → 4类15指标 (新增ErrorType+分位数)");
    }

    // ==================== 公开方法供调用 ====================

    public void recordFusion(String source) {
        intentFusionTotal.increment();
        switch (source) {
            case "RULE" -> intentFusionRule.increment();
            case "CLASSIFIER" -> intentFusionClassifier.increment();
            case "LLM" -> intentFusionLLM.increment();
            default -> intentFusionFallback.increment();
        }
    }

    public void recordDrift(boolean strong) {
        if (strong) driftStrong.increment();
        else driftDetected.increment();
    }

    public void recordBudgetExceeded() {
        budgetExceeded.increment();
    }

    public void recordToolExec(boolean success) {
        toolExecTotal.increment();
        if (success) toolExecSuccess.increment();
        else toolExecFailed.increment();
    }

    /**
     * ⭐ 按错误类型分类记录工具执行结果。
     * <p>
     * 与 {@link #recordToolExec(boolean)} 不同，此方法进一步区分：
     * <ul>
     *   <li>{@code NONE} — 成功</li>
     *   <li>{@code RETRYABLE_FAILED} — 可重试错误（超时/连接等问题）</li>
     *   <li>{@code FATAL_FAILED} — 不可恢复错误（数据不存在等）</li>
     * </ul>
     * </p>
     *
     * @param errorType 错误类型（null 视为 NONE/成功）
     */
    public void recordToolExecByError(String errorType) {
        toolExecTotal.increment();
        if (errorType == null || "NONE".equalsIgnoreCase(errorType)) {
            toolExecSuccess.increment();
        } else if ("RETRYABLE_FAILED".equalsIgnoreCase(errorType)) {
            toolExecRetryable.increment();
        } else {
            toolExecFatal.increment();
        }
    }

    /**
     * ⭐ 记录工具执行延迟（含 p50/p95/p99 分位数）。
     *
     * @param durationMs 执行耗时（毫秒）
     */
    public void recordToolLatency(long durationMs) {
        toolExecLatency.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordCircuitBreaker() {
        toolCircuitBreakerOpen.increment();
    }
}
