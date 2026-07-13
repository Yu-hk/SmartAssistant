package com.example.smartassistant.common.agent;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.error.AgentException;
import com.example.smartassistant.common.error.ErrorRecoveryService;
import com.example.smartassistant.common.error.RecoveryAction;
import com.example.smartassistant.common.metrics.AgentMetricsCollector;
import com.example.smartassistant.common.observability.OpsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Agent 工具执行器。
 * <p>
 * 从 {@link SmartReActAgent} 分离的工具执行职责，支持串行/并行执行、自动重试、
 * 工具幻觉检测、输出截断。使用虚拟线程 + 信号量限流。
 * </p>
 */
public class AgentToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentToolExecutor.class);

    /** 工具执行线程池（延迟初始化，JDK 21 虚拟线程，每工具一线程） */
    private volatile ExecutorService toolExecutor;

    /**
     * 工具并发限流闸门。
     * <p>虚拟线程执行器是无界的，用 {@link Semaphore} 保留 "单批工具最多
     * maxConcurrency 个并行" 语义。</p>
     */
    private volatile Semaphore toolConcurrencyLimiter;

    /** 运行时画像（从 Agent 传入，读取 maxConcurrency / toolTimeoutMs） */
    private final ReActProfile profile;

    /** 指标采集器 */
    private final AgentMetricsCollector metrics;

    /** 错误恢复服务（表驱动重试） */
    private final ErrorRecoveryService recoveryService;

    /** 运营指标收集器 */
    private final OpsMetrics opsMetrics;

    /** 是否启用并行执行 */
    private final boolean parallelExecution;

    public AgentToolExecutor(ReActProfile profile, AgentMetricsCollector metrics,
                             ErrorRecoveryService recoveryService, OpsMetrics opsMetrics,
                             boolean parallelExecution) {
        this.profile = profile;
        this.metrics = metrics;
        this.recoveryService = recoveryService;
        this.opsMetrics = opsMetrics;
        this.parallelExecution = parallelExecution;
    }

    // ==================== 入口 ====================

    /**
     * 执行工具调用列表（支持并行）。
     * <p>当工具数 > 1 且启用了并行执行时，使用 CompletableFuture 并行执行，
     * 结果按入参 {@code toolCalls} 的顺序收集。</p>
     */
    public List<ToolResponseMessage.ToolResponse> execute(
            List<AssistantMessage.ToolCall> toolCalls,
            Map<String, ToolCallback> toolMap) {

        if (!parallelExecution || toolCalls.size() <= 1) {
            return executeSequential(toolCalls, toolMap);
        }

        log.info("[AgentToolExecutor] 并行执行 {} 个工具 (最大并发 {})", toolCalls.size(), profile.maxConcurrency());

        List<CompletableFuture<ToolResponseMessage.ToolResponse>> futures = new ArrayList<>();
        for (var tc : toolCalls) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    toolConcurrencyLimiter.acquire();
                    try {
                        return executeWithRetry(tc, toolMap);
                    } finally {
                        toolConcurrencyLimiter.release();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(ie);
                }
            }, getExecutor()));
        }

        List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(profile.toolTimeoutMs(), TimeUnit.MILLISECONDS);

            for (var f : futures) {
                results.add(f.get());
            }
        } catch (TimeoutException e) {
            log.warn("[AgentToolExecutor] 工具批次超时 ({}ms)", profile.toolTimeoutMs());
            for (var f : futures) {
                if (f.isDone()) {
                    try { results.add(f.get()); }
                    catch (Exception ex) {
                        results.add(timeoutError());
                    }
                } else {
                    f.cancel(true);
                    results.add(timeoutError());
                }
            }
        } catch (Exception e) {
            log.error("[AgentToolExecutor] 并行执行异常: {}", e.getMessage());
            return executeSequential(toolCalls, toolMap);
        }

        return results;
    }

    // ==================== 串行执行 ====================

    private List<ToolResponseMessage.ToolResponse> executeSequential(
            List<AssistantMessage.ToolCall> toolCalls,
            Map<String, ToolCallback> toolMap) {

        List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
        for (var tc : toolCalls) {
            results.add(executeWithRetry(tc, toolMap));
        }
        return results;
    }

    // ==================== 单工具执行（带重试）====================

    /**
     * 执行单个工具调用并自动重试（基于 ErrorRecoveryService 表驱动决策）。
     */
    private ToolResponseMessage.ToolResponse executeWithRetry(
            AssistantMessage.ToolCall tc, Map<String, ToolCallback> toolMap) {

        ToolCallback callback = toolMap.get(tc.name());
        if (callback == null) {
            log.warn("[AgentToolExecutor] 未知工具: {}", tc.name());
            metrics.recordToolHallucination();
            recoveryService.logRecovery(AgentErrorCode.UNKNOWN_TOOL, RecoveryAction.CLARIFY_USER,
                    "tool=" + tc.name(), 0);
            opsMetrics.recordToolCall("smart_react", false);
            return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                    "{\"error_code\":\"UNKNOWN_TOOL\",\"message\":\"未知工具: "
                            + tc.name() + "\",\"retryable\":false}");
        }

        int attempt = 0;
        Exception lastException = null;
        String lastErrorCode = null;

        while (true) {
            attempt++;
            try {
                log.info("[AgentToolExecutor] 执行工具: {} (id={}, attempt={})",
                        tc.name(), tc.id(), attempt);
                long toolStart = System.currentTimeMillis();
                String result = callback.call(tc.arguments());
                long elapsed = System.currentTimeMillis() - toolStart;
                log.debug("[AgentToolExecutor] 工具 {} 完成 (耗时 {}ms)", tc.name(), elapsed);

                String errorCode = extractErrorCode(result);
                if (errorCode == null) {
                    String truncated = truncateResult(result, tc.name());
                    opsMetrics.recordToolCall("smart_react", true);
                    return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                            truncated != null ? truncated : "null");
                }

                lastErrorCode = errorCode;
                AgentErrorCode agentCode = AgentErrorCode.fromCode(errorCode);
                if (agentCode != null && recoveryService.shouldRetry(agentCode, attempt)) {
                    long delay = recoveryService.getRetryDelayMs(agentCode, attempt - 1);
                    log.warn("[AgentToolExecutor] 工具返回错误(第{}次): tool={}, code={}, {}ms后重试",
                            attempt, tc.name(), errorCode, delay);
                    recoveryService.logRecovery(agentCode, recoveryService.resolve(agentCode),
                            "tool=" + tc.name() + ", result=" + truncate64(result), attempt);
                    try { Thread.sleep(delay); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                log.warn("[AgentToolExecutor] 工具返回不可重试错误(尝试{}次): tool={}, code={}",
                        attempt, tc.name(), errorCode);
                opsMetrics.recordToolCall("smart_react", false);
                return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result);

            } catch (AgentException e) {
                lastErrorCode = e.getErrorCode().getCode();
                if (recoveryService.shouldRetry(e.getErrorCode(), attempt)) {
                    long delay = recoveryService.getRetryDelayMs(e.getErrorCode(), attempt - 1);
                    log.warn("[AgentToolExecutor] 工具异常(第{}次): tool={}, code={}, {}ms后重试",
                            attempt, tc.name(), e.getErrorCode().getCode(), delay);
                    recoveryService.logRecovery(e.getErrorCode(), recoveryService.resolve(e.getErrorCode()),
                            "tool=" + tc.name() + ", msg=" + e.getMessage(), attempt);
                    try { Thread.sleep(delay); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                opsMetrics.recordToolCall("smart_react", false);
                return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), e.toToolResultJson());

            } catch (Exception e) {
                lastException = e;
                AgentErrorCode code = AgentErrorCode.TOOL_EXECUTION_ERROR;
                if (recoveryService.shouldRetry(code, attempt)) {
                    long delay = recoveryService.getRetryDelayMs(code, attempt - 1);
                    log.warn("[AgentToolExecutor] 工具异常(第{}次): tool={}, {}ms后重试",
                            attempt, tc.name(), delay);
                    try { Thread.sleep(delay); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                log.error("[AgentToolExecutor] 工具执行失败(最后一次尝试): {} - {}", tc.name(), e.getMessage());
                opsMetrics.recordToolCall("smart_react", false);
                return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                        "{\"error_code\":\"TOOL_EXECUTION_ERROR\",\"message\":\""
                                + e.getMessage() + "\",\"retryable\":true}");
            }
        }

        String reason = lastErrorCode != null
                ? "工具返回错误: " + lastErrorCode
                : (lastException != null ? lastException.getMessage() : "工具执行失败");
        log.error("[AgentToolExecutor] 工具执行失败(重试{}次): tool={}, reason={}", attempt - 1, tc.name(), reason);
        opsMetrics.recordToolCall("smart_react", false);
        return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                "{\"error_code\":\"TOOL_EXECUTION_ERROR\",\"message\":\""
                        + reason + "\",\"retryable\":false}");
    }

    // ==================== 线程池管理 ====================

    private ExecutorService getExecutor() {
        if (toolExecutor == null) {
            synchronized (this) {
                if (toolExecutor == null) {
                    toolConcurrencyLimiter = new Semaphore(profile.maxConcurrency());
                    toolExecutor = Executors.newVirtualThreadPerTaskExecutor();
                }
            }
        }
        return toolExecutor;
    }

    // ==================== 静态工具方法 ====================

    /** 工具参数的稳定指纹（FNV-1a 64-bit），用于无增量检测。 */
    public static long argHash64(String args) {
        if (args == null) return 0L;
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < args.length(); i++) {
            hash ^= (args.charAt(i) & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** 从 JSON 结果中提取 error_code 字段值。 */
    public static String extractErrorCode(String result) {
        if (result == null || result.isBlank()) return null;
        String trimmed = result.trim();
        if (!trimmed.startsWith("{")) return null;
        int keyIdx = trimmed.indexOf("\"error_code\"");
        if (keyIdx < 0) return null;
        int startQuote = trimmed.indexOf('"', keyIdx + 12);
        if (startQuote < 0) return null;
        int endQuote = trimmed.indexOf('"', startQuote + 1);
        if (endQuote <= startQuote) return null;
        return trimmed.substring(startQuote + 1, endQuote);
    }

    /** 截断字符串到64字符（用于日志）。 */
    public static String truncate64(String str) {
        if (str == null) return "";
        return str.length() > 64 ? str.substring(0, 64) + "..." : str;
    }

    /** 超时错误响应。 */
    private static ToolResponseMessage.ToolResponse timeoutError() {
        return new ToolResponseMessage.ToolResponse("", "",
                "{\"error_code\":\"TOOL_TIMEOUT\",\"message\":\"工具执行超时\",\"retryable\":true}");
    }

    // ═══════════════════════════════════════════════════════════════
    // ║ 工具输出截断（保头保尾砍中间）
    // ║ 头部保留结构信息，尾部保留结论，中间大量数据被压缩。
    // ═══════════════════════════════════════════════════════════════

    /** 单次工具输出的最大字符数（超出则截断） */
    private static final int MAX_TOOL_OUTPUT_CHARS = 10_000;

    /** 截断时保留的头部比例 */
    private static final double TRUNCATE_HEAD_RATIO = 0.3;

    /** 截断时保留的尾部比例 */
    private static final double TRUNCATE_TAIL_RATIO = 0.3;

    /** 截断间隔标记 */
    private static final String TRUNCATE_MARKER = "\n\n...[中间省略 %d 字符]...\n\n";

    /**
     * 截断工具输出——保头保尾砍中间。
     */
    public static String truncateResult(String result, String toolName) {
        if (result == null || result.length() <= MAX_TOOL_OUTPUT_CHARS) {
            return result;
        }

        int totalLen = result.length();
        int headLen = (int) (totalLen * TRUNCATE_HEAD_RATIO);
        int tailLen = (int) (totalLen * TRUNCATE_TAIL_RATIO);
        int omitted = totalLen - headLen - tailLen;

        String head = result.substring(0, headLen);
        String tail = result.substring(totalLen - tailLen);
        String marker = String.format(TRUNCATE_MARKER, omitted);

        String truncated = head + marker + tail;

        log.info("[AgentToolExecutor] 工具输出截断: tool={}, original={} chars → {} chars (省略 {} chars)",
                toolName, totalLen, truncated.length(), omitted);

        return truncated;
    }
}
