package com.example.smartassistant.common.gateway.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P0 统一 LLM 调用网关。
 * <p>
 * 提供统一的超时控制、重试机制、熔断保护，不依赖具体 LLM SDK（ChatClient 无关）。
 * 调用方传入 {@link LLMExecutor} 回调即可。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * LLMCallResult result = llmGateway.call(
 *     () -> chatClient.prompt().user(msg).call().content(),
 *     "你的助手",
 *     LLMCallConfig.simple()
 * );
 * }</pre>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class AgentLLMGateway {

    private static final Logger log = LoggerFactory.getLogger(AgentLLMGateway.class);

    // ==================== 熔断器 ====================

    private final ConcurrentHashMap<String, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();

    private static final int CB_FAILURE_THRESHOLD = 5;
    private static final Duration CB_RECOVERY_TIMEOUT = Duration.ofSeconds(30);

    // ==================== 核心方法 ====================

    /**
     * 统一的 LLM 调用入口。
     *
     * @param modelCall LLM 调用回调
     * @param modelKey  模型标识（用于熔断隔离）
     * @param config    调用配置
     * @return 调用结果
     */
    public LLMCallResult call(LLMExecutor modelCall, String modelKey, LLMCallConfig config) {
        if (modelCall == null) {
            return LLMCallResult.failure("modelCall is null", 0);
        }

        String key = modelKey != null ? modelKey : "default";

        // 1. 熔断检查
        if (config.enableCircuitBreaker() && isCircuitBroken(key)) {
            log.warn("[LLMGateway] ⛔ 熔断器已打开: model={}", key);
            return LLMCallResult.failure("circuit_breaker_open: " + key, 0);
        }

        // 2. 重试循环
        Exception lastException = null;
        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            if (attempt > 0) {
                long delayMs = (long) Math.pow(2, attempt) * 200;
                log.warn("[LLMGateway] 重试({}/{}): {}ms后重试", attempt, config.maxRetries(), delayMs);
                try { Thread.sleep(delayMs); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }

            long start = System.currentTimeMillis();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<String> future = executor.submit(() -> {
                    try {
                        return modelCall.execute();
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });

                String content = future.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
                long elapsed = System.currentTimeMillis() - start;

                if (content != null) {
                    if (config.enableCircuitBreaker()) recordSuccess(key);
                    return LLMCallResult.success(content, elapsed);
                }
            } catch (TimeoutException e) {
                lastException = e;
                log.warn("[LLMGateway] ⏱ 超时(attempt={}): timeout={}", attempt, config.timeout());
            } catch (Exception e) {
                lastException = e;
                log.warn("[LLMGateway] ❌ 调用失败(attempt={}): {}", attempt,
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
        }

        // 3. 全部重试失败
        if (config.enableCircuitBreaker()) recordFailure(key);
        String errorMsg = lastException != null
                ? (lastException.getCause() != null ? lastException.getCause().getMessage() : lastException.getMessage())
                : "unknown";
        log.error("[LLMGateway] ❌ 调用彻底失败: error={}, maxRetries={}", errorMsg, config.maxRetries());
        return LLMCallResult.failure(errorMsg, 0);
    }

    // ==================== 函数式接口 ====================

    @FunctionalInterface
    public interface LLMExecutor {
        String execute() throws Exception;
    }

    // ==================== 熔断器实现 ====================

    private static class CircuitBreakerState {
        final AtomicInteger failureCount = new AtomicInteger(0);
        volatile boolean open = false;
        volatile long lastFailureTime = 0;
    }

    private boolean isCircuitBroken(String modelKey) {
        CircuitBreakerState state = circuitBreakers.get(modelKey);
        if (state == null || !state.open) return false;
        if (System.currentTimeMillis() - state.lastFailureTime > CB_RECOVERY_TIMEOUT.toMillis()) {
            state.open = false;
            state.failureCount.set(0);
            return false;
        }
        return true;
    }

    private void recordSuccess(String modelKey) {
        CircuitBreakerState state = circuitBreakers.get(modelKey);
        if (state != null) { state.failureCount.set(0); state.open = false; }
    }

    private void recordFailure(String modelKey) {
        CircuitBreakerState state = circuitBreakers.computeIfAbsent(modelKey, k -> new CircuitBreakerState());
        int count = state.failureCount.incrementAndGet();
        state.lastFailureTime = System.currentTimeMillis();
        if (count >= CB_FAILURE_THRESHOLD) {
            state.open = true;
            log.warn("[LLMGateway] 🔒 熔断器已触发: model={}, failures={}", modelKey, count);
        }
    }

    public boolean isCircuitOpen(String modelKey) {
        CircuitBreakerState state = circuitBreakers.get(modelKey);
        return state != null && state.open;
    }

    public void resetCircuitBreaker(String modelKey) {
        circuitBreakers.remove(modelKey);
    }
}
