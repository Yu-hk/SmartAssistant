package com.example.smartassistant.common.gateway.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.error.ErrorRecoveryService;
import com.example.smartassistant.common.gateway.tool.hook.ToolExecutionHook;
import com.example.smartassistant.common.gateway.tool.hook.ToolHookContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P0 统一工具执行网关。
 * <p>
 * 所有 {@code @Tool} 方法应通过此网关执行，以获得统一的：
 * <ul>
 *   <li><b>鉴权</b> — 检查调用方是否有对应 Scope 的权限</li>
 *   <li><b>标签鉴权</b> — 检查调用方标签是否匹配工具标签（Scope 鉴权的补充层）</li>
 *   <li><b>熔断</b> — 连续失败超过阈值后快速拒绝</li>
 *   <li><b>超时</b> — 异步 Future.get() 超时切断</li>
 *   <li><b>审计</b> — 每次调用记录日志（含耗时、结果）</li>
 *   <li><b>幂等</b> — 通过 IdempotencyKey 防止重复执行</li>
 * </ul>
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * String result = toolGateway.execute("refund_order", () -> {
 *     return orderTools.refundOrder(orderId);
 * }, "scope:order:write", "idem-12345");
 * }</pre>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(ToolGateway.class);

    private final ToolRegistry toolRegistry;

    /** 执行钩子链（按 @Order 排序，Spring 自动注入） */
    private final List<ToolExecutionHook> hooks;

    // 熔断器：按工具名隔离
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    // 限流器：按工具名隔离
    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    // 幂等缓存：IdempotencyKey → 结果
    private final Map<String, String> idempotencyCache = new ConcurrentHashMap<>();

    public ToolGateway(ToolRegistry toolRegistry, List<ToolExecutionHook> hooks) {
        this.toolRegistry = toolRegistry;
        this.hooks = hooks != null ? hooks : List.of();
    }

    /**
     * 执行工具调用。
     *
     * @param toolName        工具名称
     * @param executor        工具执行回调
     * @param scope           调用方 Scope（null = 不检查权限）
     * @param idempotencyKey  幂等键（null = 不检查幂等）
     * @return 工具执行结果
     * @throws ToolExecutionException 执行失败时抛出
     */
    public String execute(String toolName, ToolExecutor executor,
                          String scope, String idempotencyKey) {
        long start = System.currentTimeMillis();

        // 0. 获取工具定义
        ToolDefinition def = toolRegistry.get(toolName);
        if (def == null) {
            throw new ToolExecutionException(toolName, AgentErrorCode.TOOL_INVALID_ARGUMENT,
                    "工具未注册: " + toolName);
        }

        // 0.5 构建 Hook 上下文 + preExecute 链（按 @Order 正序执行）
        ToolHookContext context = ToolHookContext.builder()
                .toolName(toolName)
                .toolDefinition(def)
                .scope(scope)
                .idempotencyKey(idempotencyKey)
                .startTimeMs(start)
                .build();
        for (ToolExecutionHook hook : hooks) {
            hook.preExecute(context);
        }

        // 1. 幂等检查
        if (idempotencyKey != null && !def.isReadOnly()) {
            String cached = idempotencyCache.get(idempotencyKey);
            if (cached != null) {
                log.info("[ToolGateway] 🔄 幂等命中: tool={}, key={}", toolName, idempotencyKey);
                return cached;
            }
        }

        // 2. 鉴权检查（Scope 鉴权 + 标签鉴权，两者是"或"关系——只要满足任一即放行）
        boolean hasScopes = scope != null && def.getScopes() != null && def.getScopes().length > 0;
        boolean hasTags = scope != null && def.getTags() != null && def.getTags().length > 0;

        if (hasScopes || hasTags) {
            boolean scopeAuthorized = false;
            if (hasScopes) {
                for (String allowed : def.getScopes()) {
                    if (allowed.equals(scope) || allowed.equals("*")) {
                        scopeAuthorized = true;
                        break;
                    }
                }
            }
            boolean tagAuthorized = hasTags && Arrays.asList(def.getTags()).contains(scope);

            if (!scopeAuthorized && !tagAuthorized) {
                throw new ToolExecutionException(toolName, AgentErrorCode.PERMISSION_DENIED,
                        "权限不足: tool=" + toolName + ", scope=" + scope + ", tags=" + Arrays.toString(def.getTags()));
            }
        }

        // 3. 熔断检查
        if (toolFailureCount(toolName) >= 3) {
            throw new ToolExecutionException(toolName, AgentErrorCode.TOOL_EXECUTION_FAILED,
                    "熔断已打开: tool=" + toolName);
        }

        // 4. 限流检查
        if (def.getRateLimit() > 0) {
            RateLimiter limiter = rateLimiters.computeIfAbsent(toolName, k -> new RateLimiter(def.getRateLimit()));
            if (!limiter.tryAcquire()) {
                throw new ToolExecutionException(toolName, AgentErrorCode.TOOL_EXECUTION_FAILED,
                        "限流: tool=" + toolName + ", limit=" + def.getRateLimit() + "/s");
            }
        }

        // 5. 执行（带超时）
        try {
            FutureTask<String> task = new FutureTask<>(executor::execute);
            Thread thread = Thread.ofVirtual().start(task);

            String result = task.get(def.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            // 6. 审计日志
            log.info("[ToolGateway] ✅ tool={}, elapsed={}ms, risk={}, len={}",
                    toolName, elapsed, def.getRiskLevel(), result != null ? result.length() : 0);

            // 6.5 postExecute 链（按 @Order 正序执行，链式传递 result）
            context.setElapsedMs(elapsed);
            String processedResult = result;
            for (ToolExecutionHook hook : hooks) {
                processedResult = hook.postExecute(context, processedResult);
            }

            // 7. 幂等缓存（非只读操作）
            if (idempotencyKey != null && !def.isReadOnly()) {
                idempotencyCache.put(idempotencyKey, processedResult);
            }

            // 8. 熔断恢复
            recordSuccess(toolName);

            // 9. 调用计数递增（原子操作）
            def.incrementAndGetUseCount();

            return processedResult;

        } catch (TimeoutException e) {
            recordFailure(toolName);
            context.setElapsedMs(System.currentTimeMillis() - start);
            for (ToolExecutionHook hook : hooks) {
                hook.onError(context, e);
            }
            throw new ToolExecutionException(toolName, AgentErrorCode.TOOL_EXECUTION_FAILED,
                    "工具超时: " + def.getTimeout());
        } catch (Exception e) {
            recordFailure(toolName);
            context.setElapsedMs(System.currentTimeMillis() - start);
            for (ToolExecutionHook hook : hooks) {
                hook.onError(context, e);
            }
            throw new ToolExecutionException(toolName, AgentErrorCode.TOOL_EXECUTION_FAILED,
                    "工具执行失败: " + e.getMessage());
        }
    }

    /** 工具执行回调接口 */
    @FunctionalInterface
    public interface ToolExecutor {
        String execute() throws Exception;
    }

    // ==================== 熔断器 ====================

    private static class CircuitBreaker {
        final AtomicLong failureCount = new AtomicLong(0);
        volatile boolean open = false;
    }

    private long toolFailureCount(String toolName) {
        CircuitBreaker cb = circuitBreakers.get(toolName);
        return cb != null && cb.open ? 999 : 0;
    }

    private void recordSuccess(String toolName) {
        circuitBreakers.remove(toolName);
    }

    private void recordFailure(String toolName) {
        CircuitBreaker cb = circuitBreakers.computeIfAbsent(toolName, k -> new CircuitBreaker());
        long count = cb.failureCount.incrementAndGet();
        if (count >= 3) {
            cb.open = true;
            log.warn("[ToolGateway] 🔒 工具熔断: tool={}, failures={}", toolName, count);
        }
    }

    // ==================== 限流器 ====================

    private static class RateLimiter {
        private final int permitsPerSecond;
        private long lastRefillTime = System.nanoTime();
        private double availablePermits;

        RateLimiter(int permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond;
            this.availablePermits = permitsPerSecond;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            double elapsed = (now - lastRefillTime) / 1_000_000_000.0;
            availablePermits = Math.min(permitsPerSecond,
                    availablePermits + elapsed * permitsPerSecond);
            lastRefillTime = now;

            if (availablePermits >= 1) {
                availablePermits--;
                return true;
            }
            return false;
        }
    }

    /** 清空幂等缓存 */
    public void clearIdempotencyCache() {
        idempotencyCache.clear();
    }
}
