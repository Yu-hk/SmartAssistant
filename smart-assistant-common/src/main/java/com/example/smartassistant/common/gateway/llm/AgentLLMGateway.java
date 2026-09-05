package com.example.smartassistant.common.gateway.llm;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * P0 统一 LLM 调用网关。
 * <p>
 * 提供统一的超时控制、重试机制、熔断保护，不依赖具体 LLM SDK（ChatClient 无关）。
 * 弹性策略委托给 Resilience4j，网关只保留项目级返回契约和日志语义。
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

    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

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

        CircuitBreaker circuitBreaker = config.enableCircuitBreaker()
                ? circuitBreakers.computeIfAbsent(key, this::newCircuitBreaker)
                : null;
        Retry retry = newRetry(key, config);
        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(config.timeout())
                .cancelRunningFuture(true)
                .build());

        long overallStart = System.currentTimeMillis();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        var requestContext = MDC.getCopyOfContextMap();
        try {
            Callable<String> singleAttempt = () -> timeLimiter.executeFutureSupplier(
                    () -> executor.submit(() -> {
                        var previous = MDC.getCopyOfContextMap();
                        try {
                            if (requestContext == null) MDC.clear();
                            else MDC.setContextMap(requestContext);
                            return modelCall.execute();
                        } finally {
                            if (previous == null) MDC.clear();
                            else MDC.setContextMap(previous);
                        }
                    }));
            Callable<String> decorated = Retry.decorateCallable(retry, singleAttempt);
            if (circuitBreaker != null) {
                // 熔断器位于重试器外层：一次业务调用只有在全部尝试失败后才计为一次失败。
                decorated = CircuitBreaker.decorateCallable(circuitBreaker, decorated);
            }

            String content = decorated.call();
            long elapsed = System.currentTimeMillis() - overallStart;
            return content != null
                    ? LLMCallResult.success(content, elapsed)
                    : LLMCallResult.failure("empty response", elapsed);
        } catch (Exception e) {
            Throwable cause = unwrap(e);
            String errorMsg;
            if (cause instanceof TimeoutException) {
                errorMsg = "timeout after " + config.timeout().toMillis() + "ms";
            } else if (cause instanceof CallNotPermittedException) {
                errorMsg = "circuit_breaker_open: " + key;
            } else {
                errorMsg = cause.getMessage();
                if (errorMsg == null || errorMsg.isBlank()) {
                    errorMsg = cause.getClass().getSimpleName();
                }
            }
            long elapsed = System.currentTimeMillis() - overallStart;
            log.error("[LLMGateway] ❌ 调用彻底失败: model={}, error={}, maxRetries={}",
                    key, errorMsg, config.maxRetries());
            return LLMCallResult.failure(errorMsg, elapsed);
        } finally {
            // TimeLimiter 会取消 Future；shutdownNow 确保不等待不响应中断的底层网络任务。
            executor.shutdownNow();
        }
    }

    // ==================== 函数式接口 ====================

    @FunctionalInterface
    public interface LLMExecutor {
        String execute() throws Exception;
    }

    // ==================== Resilience4j 中间件配置 ====================

    private CircuitBreaker newCircuitBreaker(String modelKey) {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(CB_FAILURE_THRESHOLD)
                .minimumNumberOfCalls(CB_FAILURE_THRESHOLD)
                .failureRateThreshold(100.0f)
                .waitDurationInOpenState(CB_RECOVERY_TIMEOUT)
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreaker breaker = CircuitBreaker.of("llm-" + modelKey, cbConfig);
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("[LLMGateway] 熔断状态变化: model={}, transition={}",
                        modelKey, event.getStateTransition()));
        return breaker;
    }

    private Retry newRetry(String modelKey, LLMCallConfig config) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(config.maxRetries() + 1)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(400L, 2.0))
                .retryOnResult(Objects::isNull)
                .build();
        Retry retry = Retry.of("llm-" + modelKey, retryConfig);
        retry.getEventPublisher().onRetry(event ->
                log.warn("[LLMGateway] 重试({}/{}): model={}, lastError={}",
                        event.getNumberOfRetryAttempts(), config.maxRetries(), modelKey,
                        event.getLastThrowable() != null
                                ? event.getLastThrowable().getMessage() : "empty response"));
        return retry;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public boolean isCircuitOpen(String modelKey) {
        CircuitBreaker breaker = circuitBreakers.get(modelKey);
        if (breaker == null) return false;
        return breaker.getState() == CircuitBreaker.State.OPEN
                || breaker.getState() == CircuitBreaker.State.FORCED_OPEN;
    }

    public void resetCircuitBreaker(String modelKey) {
        CircuitBreaker breaker = circuitBreakers.remove(modelKey);
        if (breaker != null) breaker.reset();
    }
}
