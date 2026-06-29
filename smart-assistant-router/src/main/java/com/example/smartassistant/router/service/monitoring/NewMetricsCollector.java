package com.example.smartassistant.router.service.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

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
    private final Counter toolCircuitBreakerOpen;

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
        this.toolCircuitBreakerOpen = Counter.builder("a2a_tool_circuit_breaker")
                .description("Tool circuit breaker opened")
                .register(meterRegistry);

        log.info("[Metrics] 新指标采集器初始化完成: 4类12指标");
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

    public void recordCircuitBreaker() {
        toolCircuitBreakerOpen.increment();
    }
}
