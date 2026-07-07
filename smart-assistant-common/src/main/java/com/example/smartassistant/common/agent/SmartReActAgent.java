/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.error.AgentException;
import com.example.smartassistant.common.error.ErrorRecoveryService;
import com.example.smartassistant.common.error.PromptInjectionBlockedException;
import com.example.smartassistant.common.error.RecoveryAction;
import com.example.smartassistant.common.memory.ConversationSummaryStore;
import com.example.smartassistant.common.metrics.AgentMetricsCollector;
import com.example.smartassistant.common.tool.ToolGroupManager;
import com.example.smartassistant.common.trace.TraceSpan;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 自研 ReAct 循环执行器。
 * <p>
 * 替代 Spring AI Alibaba 的 {@code ReactAgent}，提供完全可控的循环逻辑，
 * 支持迭代次数限制、超时保护、Token 预算追踪、工具幻觉处理。
 * </p>
 * <p>
 * ⭐ 运行时画像（{@link ReActProfile}）：通过 {@link #withProfile(String, ReActProfileRegistry)}
 * 按入口（order / general / product / mcp）差异化配置步数、超时、预算、并发，
 * 实现「步数 / 预算按入口分级」。未配置时回退 {@link ReActProfile#DEFAULT}，行为不变。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * SmartReActAgent agent = new SmartReActAgent(chatModel);
 * String result = agent
 *     .withMaxIterations(10)
 *     .withTimeoutMs(60_000)
 *     .withProfile("order", reactProfileRegistry)   // 可选：入口分级覆盖
 *     .execute(userMessage, systemPrompt, toolCallbacks);
 * </pre>
 *
 * <h3>与 ReactAgent.call() 对比</h3>
 * <ul>
 *   <li>迭代次数限制 — 默认最多 10 轮工具调用（可按入口分级收紧）</li>
 *   <li>超时保护 — 默认 60 秒强制退出</li>
 *   <li>Token 预算 — 可追踪累积消耗，超过 80% 上下文窗口时自动终止</li>
 *   <li>同工具同参数去重 — 连续无增量检测基于「名称+参数」指纹，防故障重放风暴</li>
 *   <li>工具幻觉 — 不存在的工具名返回结构化错误，让 LLM 自纠正</li>
 *   <li>工具错误 — 执行异常返回 {@code retryable: true} 结构化错误</li>
 * </ul>
 */
public class SmartReActAgent {

    private static final Logger log = LoggerFactory.getLogger(SmartReActAgent.class);

    /** 默认最大迭代次数 */
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    /** 默认超时时间（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 60_000;
    /** 默认 Token 预算：模型上下文窗口的百分比 */
    private static final double DEFAULT_TOKEN_BUDGET_RATIO = 0.8;
    /** 默认上下文窗口大小（DeepSeek V4-Flash 1M，实际建议用 128K） */
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    /** 默认触发压缩的消息数阈值 */
    private static final int DEFAULT_COMPRESS_THRESHOLD = 20;
    /** 默认压缩时保留的完整轮次数 */
    private static final int DEFAULT_KEEP_ROUNDS = 3;
    /** 摘要生成的最大原始内容字符数 */
    private static final int MAX_SUMMARY_INPUT_CHARS = 6_000;
    /** 默认最大并发工具数 */
    private static final int DEFAULT_MAX_CONCURRENCY = 4;
    /** 默认单个工具超时（毫秒） */
    private static final long DEFAULT_TOOL_TIMEOUT_MS = 30_000;
    /** ⭐ 连续无增量检测阈值：连续几次工具调用结果相似时强制停止 */
    private static final int NO_INCREMENT_LIMIT = 2;

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 工具输出截断常量（保头保尾砍中间）
    // ═══════════════════════════════════════════════════════════════

    /** 单次工具输出的最大 token 数（超出则截断），参考文章二 L1 实时截断 */
    private static final int MAX_TOOL_OUTPUT_CHARS = 10_000;

    /** 截断时保留的头部比例 */
    private static final double TRUNCATE_HEAD_RATIO = 0.3;

    /** 截断时保留的尾部比例 */
    private static final double TRUNCATE_TAIL_RATIO = 0.3;

    /** 截断间隔标记 */
    private static final String TRUNCATE_MARKER = "\n\n...[中间省略 %d 字符]...\n\n";

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 双阈值压缩常量（Scoped + Full Window）
    // ═══════════════════════════════════════════════════════════════

    /** Scoped 阈值：窗口净增长超过此比例时触发压缩 */
    private static final double SCOPED_RATIO = 0.9;

    /** Full Window 阈值：总 token 数超过此比例时强制压缩 */
    private static final double FULL_WINDOW_RATIO = 0.95;

    /** 压缩后重置的 prefill 基线（首次压缩后设为摘要占据的 token） */
    private int prefillBaseline = 0;

    private final ChatModel chatModel;

    /** ⭐ ChatClient（可选，含 Advisor 链）— 设置后优先使用，使 TokenUsage/ThinkingCollector 等 Advisor 生效 */
    private ChatClient chatClient;

    /** ⭐ 运行时画像（入口分级）：集中管理 maxIterations/timeoutMs/预算/并发等，支持按入口差异化 */
    private ReActProfile profile = ReActProfile.DEFAULT;

    private boolean trackTokenBudget = true;
    /** 是否启用上下文压缩 */
    private boolean enableCompress = true;
    /** 触发压缩的消息数阈值 */
    private int compressThreshold = DEFAULT_COMPRESS_THRESHOLD;
    /** 压缩时保留的完整轮次数 */
    private int keepRounds = DEFAULT_KEEP_ROUNDS;
    /** 是否启用并行工具执行 */
    private boolean parallelExecution = true;

    /** 预配置的系统提示词（可选，可在 execute 时传入） */
    private String presetSystemPrompt;
    /** 预配置的工具列表（可选，可在 execute 时传入） */
    private List<ToolCallback> presetTools;

    /** ⭐ 可选的指标采集器 */
    private AgentMetricsCollector metrics = new AgentMetricsCollector() {};

    /** ⭐ 错误恢复服务（表驱动恢复，默认使用内置静态实例） */
    private ErrorRecoveryService recoveryService = ErrorRecoveryService.DEFAULT;

    /** ⭐ 工具组管理器（可选，用于按需激活工具组） */
    private ToolGroupManager toolGroupManager;

    /** ⭐ 追踪跨度注册表（可选，用于生成 Jaeger 嵌套跨度） */
    private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

    /** ⭐ 预计算压缩结果（PrecomputedCompact：后台异步压缩，下一轮直接使用） */
    private volatile CompletableFuture<List<Message>> precomputedCompactFuture;

    /** ⭐ 递归滚动摘要链（#1 对话历史 + #3 递归滚动）：每轮压缩生成的摘要累加到此链 */
    private final List<String> summaryChain = new ArrayList<>();
    /** 每触发一次压缩后递增的摘要代数 */
    private int summaryGeneration = 0;

    /** ⭐ 对话摘要持久化存储（可选：方案 A — 压缩摘要写入向量数据库） */
    private ConversationSummaryStore summaryStore;

    public SmartReActAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    // ==================== 配置方法 ====================

    public SmartReActAgent withMaxIterations(int maxIterations) {
        this.profile = this.profile.withMaxIterations(maxIterations);
        return this;
    }

    public SmartReActAgent withTimeoutMs(long timeoutMs) {
        this.profile = this.profile.withTimeoutMs(timeoutMs);
        return this;
    }

    /** ⭐ 按入口 key 应用画像（入口分级）；registry 为 null 或 key 不存在时保持当前画像（默认 DEFAULT）。 */
    public SmartReActAgent withProfile(String entryKey, ReActProfileRegistry registry) {
        if (registry != null) {
            ReActProfile p = registry.get(entryKey);
            if (p != null) {
                this.profile = p;
            }
        }
        return this;
    }

    /** ⭐ 直接应用指定画像。 */
    public SmartReActAgent withProfile(ReActProfile profile) {
        if (profile != null) {
            this.profile = profile;
        }
        return this;
    }

    public SmartReActAgent withTokenBudget(boolean track, double budgetRatio, int contextWindow) {
        this.trackTokenBudget = track;
        this.profile = this.profile.withTokenBudgetRatio(budgetRatio).withContextWindow(contextWindow);
        return this;
    }

    /**
     * 配置上下文压缩。
     *
     * @param enable    是否启用
     * @param threshold 触发压缩的消息数阈值
     * @param keepRounds 压缩时保留的完整轮次数
     * @return this
     */
    public SmartReActAgent withCompress(boolean enable, int threshold, int keepRounds) {
        this.enableCompress = enable;
        this.compressThreshold = threshold;
        this.keepRounds = keepRounds;
        return this;
    }

    /**
     * 配置并行工具执行。
     *
     * @param enable     是否启用并行
     * @param concurrency 最大并发数
     * @param toolTimeoutMs 单个工具超时（毫秒）
     * @return this
     */
    public SmartReActAgent withParallelExecution(boolean enable, int concurrency, long toolTimeoutMs) {
        this.parallelExecution = enable;
        this.profile = this.profile.withMaxConcurrency(concurrency).withToolTimeoutMs(toolTimeoutMs);
        return this;
    }

    /**
     * 预配置系统提示词和工具列表。
     * 配置后可直接调用 {@link #execute(String)} 简化调用。
     *
     * @param systemPrompt 系统提示词
     * @param tools        可用工具列表
     * @return this
     */
    public SmartReActAgent withPreset(String systemPrompt, List<ToolCallback> tools) {
        this.presetSystemPrompt = systemPrompt;
        this.presetTools = tools;
        return this;
    }

    /**
     * 设置指标采集器。
     *
     * @param metrics 指标采集器实现（通常由各模块的 MetricsCollector 实现）
     * @return this
     */
    public SmartReActAgent withMetrics(AgentMetricsCollector metrics) {
        if (metrics != null) {
            this.metrics = metrics;
        }
        return this;
    }

    /**
     * 设置错误恢复服务（用于表驱动错误码→恢复策略路由）。
     * 默认使用 {@link ErrorRecoveryService#DEFAULT}，通常无需覆写。
     *
     * @param recoveryService 错误恢复服务
     * @return this
     */
    public SmartReActAgent withRecoveryService(ErrorRecoveryService recoveryService) {
        if (recoveryService != null) {
            this.recoveryService = recoveryService;
        }
        return this;
    }

    /**
     * 设置工具组管理器（用于按需激活工具组，减少 LLM Schema 占用）。
     * 激活的组工具会注入 ReAct 循环，未激活组对 LLM 不可见。
     *
     * @param toolGroupManager 工具组管理器
     * @return this
     */
    public SmartReActAgent withToolGroupManager(ToolGroupManager toolGroupManager) {
        this.toolGroupManager = toolGroupManager;
        return this;
    }

    public SmartReActAgent withObservationRegistry(ObservationRegistry registry) {
        if (registry != null) this.observationRegistry = registry;
        return this;
    }

    /** 配置对话摘要持久化存储（方案 A：压缩摘要写入向量数据库） */
    public SmartReActAgent withSummaryStore(ConversationSummaryStore summaryStore) {
        this.summaryStore = summaryStore;
        return this;
    }

    /**
     * 配置 ChatClient（含 Advisor 链）。
     *
     * <p>设置后，Agent 使用 ChatClient (含 TokenUsageAdvisor / ThinkingCollectorAdvisor 等)
     * 替换原始的 {@code chatModel.call()}，使 Advisor 链生效。
     * 建议在创建 Agent 时通过 {@code ChatClient.builder(chatModel).defaultAdvisors(...).build()} 构建。
     *
     * @param chatClient 含 Advisor 链的 ChatClient 实例
     * @return this
     */
    public SmartReActAgent withChatClient(ChatClient chatClient) {
        this.chatClient = chatClient;
        return this;
    }

    // ==================== execute 重载 ====================

    /**
     * 执行 ReAct 循环（使用预设的 prompt 和 tools）。
     *
     * @param userMessage 用户输入
     * @return 最终回答
     */
    public String execute(String userMessage) {
        if (presetSystemPrompt == null || presetTools == null) {
            throw new IllegalStateException("未配置 presetSystemPrompt/presetTools，请使用 execute(userMessage, systemPrompt, tools)");
        }
        return execute(userMessage, presetSystemPrompt, presetTools);
    }

    // ==================== 核心循环 ====================

    /**
     * 执行 ReAct 循环。
     *
     * @param userMessage  用户输入
     * @param systemPrompt 系统提示词
     * @param tools        可用工具列表
     * @return 最终回答或超时/预算耗尽提示
     */
    public String execute(String userMessage, String systemPrompt, List<ToolCallback> tools) {
        // ⭐ 当有 ToolGroupManager 时，使用活跃组的工具替换平坦列表
        List<ToolCallback> effectiveTools = tools;
        String enhancedPrompt = systemPrompt;
        if (toolGroupManager != null) {
            effectiveTools = toolGroupManager.getActiveTools();
            String groupDesc = toolGroupManager.getActiveGroupsDescription();
            enhancedPrompt = systemPrompt + "\n\n【当前可用的工具组】\n" + groupDesc;
            log.info("[SmartReActAgent] 使用 ToolGroup 模式: activeGroups={}, tools={}",
                    toolGroupManager.getActiveGroupNames(), effectiveTools.size());
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(enhancedPrompt));
        messages.add(new UserMessage(userMessage));

        long startTime = System.currentTimeMillis();
        int iteration = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        long maxBudgetTokens = (long) (profile.contextWindow() * profile.tokenBudgetRatio());

        // ⭐ 工具循环检测：记录最近工具调用的哈希，用于检测连续无增量
        String lastToolCallHash = null;
        int noIncrementCount = 0;

        // 预构建 ToolCallingChatOptions（每次循环复用）
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(effectiveTools.toArray(new ToolCallback[0]))
                .build();

        // 构建工具名查找缓存（加速）
        Map<String, ToolCallback> toolMap = new ConcurrentHashMap<>();
        for (ToolCallback tc : effectiveTools) {
            toolMap.put(tc.getToolDefinition().name(), tc);
        }

        while (iteration < profile.maxIterations()) {
            iteration++;
            long elapsed = System.currentTimeMillis() - startTime;

            // ⭐ 超时检查
            if (elapsed > profile.timeoutMs()) {
                log.warn("[SmartReActAgent] ⏰ 超时 ({}ms, 迭代 {} 次)", elapsed, iteration);
                metrics.recordTimeout();
                recoveryService.logRecovery(AgentErrorCode.SYSTEM_AGENT_TIMEOUT, RecoveryAction.FALLBACK_AGENT,
                        "elapsed=" + elapsed + "ms, iteration=" + iteration, iteration);
                return recoveryService.resolveUserMessage(AgentErrorCode.SYSTEM_AGENT_TIMEOUT, null);
            }

            // ⭐ Token 预算检查
            if (trackTokenBudget && (totalInputTokens + totalOutputTokens) > maxBudgetTokens) {
                log.warn("[SmartReActAgent] Token 预算耗尽 (输入={}, 输出={}, 上限={})",
                        totalInputTokens, totalOutputTokens, maxBudgetTokens);
                recoveryService.logRecovery(AgentErrorCode.SYSTEM_BUDGET_EXCEEDED, RecoveryAction.CLARIFY_USER,
                        "input=" + totalInputTokens + ", output=" + totalOutputTokens, iteration);
                return recoveryService.resolveUserMessage(AgentErrorCode.SYSTEM_BUDGET_EXCEEDED, null);
            }

            // ⭐ 上下文压缩检查（双阈值：Scoped 提前触发 + Full Window 绝对兜底）
            // 参考文章二：Scoped 管增速，Full Window 做绝对兜底
            // Scoped 阈值：净增长超过窗口的 90% 时触发
            // Full Window 阈值：消息数超过硬上限时触发
            int scopedThreshold = (int) (prefillBaseline
                    + (compressThreshold - prefillBaseline) * SCOPED_RATIO);
            boolean scopedTriggered = enableCompress
                    && prefillBaseline > 0
                    && messages.size() > scopedThreshold;
            boolean fullWindowTriggered = enableCompress
                    && messages.size() > compressThreshold;

            if (scopedTriggered || fullWindowTriggered) {
                // 优先使用后台预计算的结果，避免阻塞
                List<Message> compressed = null;
                if (precomputedCompactFuture != null && precomputedCompactFuture.isDone()) {
                    try {
                        compressed = precomputedCompactFuture.get();
                        precomputedCompactFuture = null;
                    } catch (Exception e) {
                        log.debug("[SmartReActAgent] 预计算压缩结果无效，回退同步: {}", e.getMessage());
                    }
                }
                if (compressed == null) {
                    compressed = compressHistory(messages);
                }
                if (compressed != messages) {
                    log.info("[SmartReActAgent] 上下文压缩: {} → {} 条消息 (Scoped={}, FullWindow={})",
                            messages.size(), compressed.size(), scopedTriggered, fullWindowTriggered);
                    messages = compressed;
                    metrics.recordContextCompression();
                    // ⭐ 压缩后更新 prefill 基线：新基线 = 压缩后的消息数 + 系统消息
                    prefillBaseline = messages.size();
                }
            }

            log.info("[SmartReActAgent] 第 {} 轮迭代开始 (已耗时 {}ms, 消息数 {})", iteration, elapsed, messages.size());

            metrics.recordIteration(iteration);

            // ⭐ 调用 LLM（带追踪跨度 + 优先使用 ChatClient/Advisor 链）
            ChatResponse response;
            long llmStart = System.currentTimeMillis();
            try {
                injectToolsToModel(effectiveTools);
                final List<Message> callMessages = messages;
                final ToolCallingChatOptions callOptions = options;

                if (chatClient != null) {
                    // ⭐ 使用 ChatClient（含 Advisor 链：TokenUsage/ThinkingCollector/PromptAudit）
                    // 通过 tools() 动态传递工具列表，通过 options() 传模型参数
                    var spec = chatClient.prompt()
                            .messages(callMessages);
                    if (!effectiveTools.isEmpty()) {
                        spec = spec.tools(effectiveTools.toArray(new ToolCallback[0]));
                    }
                    response = spec.call().chatResponse();
                } else {
                    // 兼容旧调用：直接使用 ChatModel
                    response = TraceSpan.of(observationRegistry, "agent-llm-call")
                            .run(() -> chatModel.call(new Prompt(callMessages, callOptions)));
                }
            } catch (Exception e) {
                if (e instanceof PromptInjectionBlockedException pib) {
                    // ⭐ 内容安全护栏拦截：返回友好提示而非模型故障
                    log.warn("[SmartReActAgent] 输入被内容安全护栏拦截: {}", pib.getMessage());
                    metrics.recordIteration(iteration);
                    return "⚠️ 您的输入已被内容安全策略拦截：" + pib.getMessage();
                }
                log.error("[SmartReActAgent] LLM 调用失败: {}", e.getMessage());
                recoveryService.logRecovery(AgentErrorCode.MODEL_CALL_FAILED, RecoveryAction.RETRY_BACKOFF,
                        e.getMessage(), iteration);
                return recoveryService.resolveUserMessage(AgentErrorCode.MODEL_CALL_FAILED, null);
            }
            long llmElapsed = System.currentTimeMillis() - llmStart;
            metrics.recordInferenceLatency(llmElapsed);

            if (response == null || response.getResult() == null) {
                log.warn("[SmartReActAgent] LLM 返回空");
                continue;
            }

            // ⭐ 追踪 Token 消耗
            if (trackTokenBudget && response.getMetadata() != null
                    && response.getMetadata().getUsage() != null) {
                int inTokens = response.getMetadata().getUsage().getPromptTokens();
                int outTokens = response.getMetadata().getUsage().getCompletionTokens();
                totalInputTokens += inTokens;
                totalOutputTokens += outTokens;
                log.debug("[SmartReActAgent] Token 累计: 输入={}, 输出={}",
                        totalInputTokens, totalOutputTokens);
                metrics.recordTokenUsage(inTokens, outTokens);
            }

            AssistantMessage assistantMsg = response.getResult().getOutput();
            var toolCalls = assistantMsg.getToolCalls();

            // ⭐ 没有工具调用 → 最终回答
            if (toolCalls == null || toolCalls.isEmpty()) {
                String answer = assistantMsg.getText();
                log.info("[SmartReActAgent] 最终回答 (迭代 {} 轮, 耗时 {}ms, Token 输入={}, 输出={})",
                        iteration, elapsed, totalInputTokens, totalOutputTokens);
                return answer;
            }

            log.debug("[SmartReActAgent] 收到 {} 个工具调用", toolCalls.size());

            // ⭐ 将 assistant 的 tool_call 请求加入对话
            messages.add(assistantMsg);

            // ⭐ 执行工具（支持并行，带追踪跨度）
            List<ToolResponseMessage.ToolResponse> toolResponses = TraceSpan.of(observationRegistry, "agent-tool-execute")
                    .run(() -> executeTools(toolCalls, toolMap));

            // ⭐ G1 连续无增量检测：防止重复调同类工具陷入循环。
            // 哈希键加入参数指纹 —— 仅"名称 + 参数完全一致"判定为无增量，
            // 实现文章《生产级 Agent 架构实战》要求的"同工具同参数去重"，
            // 避免接口故障时同一请求被反复重放形成调用风暴。
            String currentHash = toolCalls.stream()
                    .map(tc -> tc.name() + "(" + Long.toHexString(argHash64(tc.arguments())) + ")")
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            if (currentHash.equals(lastToolCallHash)) {
                noIncrementCount++;
                if (noIncrementCount >= NO_INCREMENT_LIMIT) {
                    log.warn("[SmartReActAgent] 连续 {} 次相同工具调用，强制停止", noIncrementCount);
                    recoveryService.logRecovery(AgentErrorCode.SYSTEM_NO_INCREMENT, RecoveryAction.RETRY_ALTERNATIVE,
                            "consecutive=" + noIncrementCount, iteration);
                    return recoveryService.resolveUserMessage(AgentErrorCode.SYSTEM_NO_INCREMENT, null);
                }
            } else {
                lastToolCallHash = currentHash;
                noIncrementCount = 0;
            }

            messages.add(ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build());

            // ⭐ PrecomputedCompact：消息数接近阈值时后台异步预压缩
            if (enableCompress && precomputedCompactFuture == null
                    && messages.size() > compressThreshold - 3) {
                List<Message> snapshot = new ArrayList<>(messages);
                precomputedCompactFuture = CompletableFuture.supplyAsync(() -> compressHistory(snapshot));
            }
            // 继续循环
        }

        // ⭐ 达到最大迭代次数
        log.warn("[SmartReActAgent] 达到最大迭代次数 {}", profile.maxIterations());
        metrics.recordMaxIterationHit();
        recoveryService.logRecovery(AgentErrorCode.SYSTEM_MAX_ITERATIONS, RecoveryAction.FALLBACK_AGENT,
                "maxIterations=" + profile.maxIterations(), profile.maxIterations());
        return recoveryService.resolveUserMessage(AgentErrorCode.SYSTEM_MAX_ITERATIONS, null);
    }

    // ==================== 上下文压缩 ====================

    /** ⭐ 增强摘要生成提示词（9 段式，参考 Claude Code 设计） */
    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成结构化摘要。

            必须包含以下 9 个部分：
            1. 用户的核心诉求与初始目标
            2. 关键技术概念与业务上下文
            3. 涉及的文件、服务或数据
            4. 错误与修复记录
            5. 问题解决过程（哪些成功了，哪些失败了）
            6. 用户的关键反馈与偏好
            7. 已完成的待办事项
            8. 仍未解决的问题或待办
            9. 后续建议与下一步计划

            要求：
            - 按以上 9 个部分编号输出，每个部分 1-3 句
            - 保留所有重要的技术细节、错误信息和用户反馈
            - 不要遗漏已完成的工具调用及其核心结果
            - 如果某部分无内容，标注「无」
            不要复述每条原文，不要保留无关闲聊。
            """;

    /**
     * 压缩对话历史（带 MicroCompact 工具结果清理 + 增量更新）。
     * <p>
     * 策略：
     * <ol>
     *   <li><b>增量更新</b>：如果已存在摘要，只对新内容追加压缩</li>
     *   <li><b>MicroCompact</b>：替换旧工具结果为简洁摘要，保留调用结构</li>
     *   <li><b>LLM 摘要</b>：用 9 段式 SUMMARY_PROMPT 生成结构化摘要</li>
     * </ol>
     * 切割点保证落在完整轮次边界，不拆散 tool_call/tool_result 对。
     *
     * @param messages 当前消息列表
     * @return 压缩后的消息列表（若无需压缩则返回原列表引用）
     */
    private List<Message> compressHistory(List<Message> messages) {
        // 至少需要: System + User + 至少 2 轮工具调用才值得压缩
        if (messages.size() < 6) return messages;

        // ⭐ 检测是否已存在摘要（增量更新模式）
        String existingSummary = findExistingSummary(messages);

        // ⭐ 从末尾向前扫描，定位保留的起始索引
        int keepStart = findKeepStart(messages);
        if (keepStart <= 1) return messages; // 所有消息都在保留范围内

        // ⭐ 将保留起点之前的消息（不含 SystemMessage）转为文本
        StringBuilder rawBuilder = new StringBuilder();
        // 如果存在旧摘要，先加入
        if (existingSummary != null) {
            rawBuilder.append("【已有摘要】\n").append(existingSummary).append("\n\n");
            rawBuilder.append("【新对话内容】\n");
        } else if (!summaryChain.isEmpty()) {
            // #3: 递归滚动 — 从摘要链中取上一代摘要
            rawBuilder.append("【上一代摘要】\n").append(summaryChain.get(0)).append("\n\n");
            rawBuilder.append("【新对话内容】\n");
        }
        for (int i = 1; i < keepStart; i++) {
            Message msg = messages.get(i);
            String role;
            String content;
            if (msg instanceof UserMessage u) {
                role = "用户";
                content = u.getText();
            } else if (msg instanceof AssistantMessage a) {
                role = "助手";
                content = a.getText() != null ? a.getText() : "(工具调用)";
            } else if (msg instanceof ToolResponseMessage) {
                role = "工具结果";
                content = "(已压缩)";
            } else {
                role = "其他";
                content = "";
            }
            if (!content.isBlank()) {
                rawBuilder.append(role).append("：").append(content).append("\n");
            }
        }

        String rawText = rawBuilder.toString();
        if (rawText.isBlank()) return messages;

        // ⭐ 截断过长的原始内容（防摘要请求超出上下文窗口）
        String inputForSummary;
        boolean truncated = false;
        if (rawText.length() > MAX_SUMMARY_INPUT_CHARS) {
            inputForSummary = rawText.substring(0, MAX_SUMMARY_INPUT_CHARS);
            truncated = true;
            log.warn("[SmartReActAgent] 摘要输入过长: {} > {}，截断", rawText.length(), MAX_SUMMARY_INPUT_CHARS);
        } else {
            inputForSummary = rawText;
        }

        // ⭐ 调用 LLM 生成摘要
        String summary;
        try {
            ChatResponse summaryResponse = chatModel.call(new Prompt(
                    SUMMARY_PROMPT + "\n\n【对话内容】\n" + inputForSummary));
            summary = summaryResponse.getResult().getOutput().getText();
            if (summary == null || summary.isBlank()) {
                log.warn("[SmartReActAgent] 摘要生成为空，跳过压缩");
                return messages;
            }
            if (truncated) {
                summary += "\n\n(部分对话因过长被截断，截断部分未纳入摘要)";
            }

            // ⭐ #1: 将当前摘要加入摘要链（#3: 递归滚动 — 每生成一代替换上一代）
            summaryGeneration++;
            synchronized (summaryChain) {
                if (summaryGeneration <= 1) {
                    summaryChain.add(summary);
                } else {
                    // 滚动替换：只有最新的摘要保留在链中，前面的由摘要自身携带历史信息
                    summaryChain.set(0, summary);
                }
            }
            log.info("[SmartReActAgent] 摘要链: 第{}代, 长度={}", summaryGeneration, summary.length());

            // ⭐ 方案 A: 持久化存储摘要到向量数据库（异步安全，异常静默降级）
            if (summaryStore != null) {
                try {
                    summaryStore.store(null, null, null, summary, summaryGeneration);
                } catch (Exception e) {
                    log.warn("[SmartReActAgent] 摘要持久化失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[SmartReActAgent] 摘要生成失败: {}，跳过压缩", e.getMessage());
            return messages; // 降级：不压缩
        }

        // ⭐ 重建消息列表
        List<Message> compressed = new ArrayList<>();
        compressed.add(messages.get(0)); // SystemMessage
        compressed.add(new UserMessage("以下是对之前对话的摘要：\n\n" + summary));
        compressed.add(new AssistantMessage("好的，我已理解之前的对话内容，继续当前任务。"));
        // 追加保留的最近消息
        for (int i = keepStart; i < messages.size(); i++) {
            compressed.add(messages.get(i));
        }

        return compressed;
    }

    /**
     * 检测消息列表中是否已存在摘要（用于增量更新）。
     * <p>
     * 查找格式为 "以下是对之前对话的摘要：\n\n..." 的 UserMessage。
     *
     * @return 已有摘要文本；不存在时返回 null
     */
    private String findExistingSummary(List<Message> messages) {
        for (Message msg : messages) {
            if (msg instanceof UserMessage u) {
                String text = u.getText();
                if (text != null && text.startsWith("以下是对之前对话的摘要：")) {
                    return text.replace("以下是对之前对话的摘要：\n\n", "").trim();
                }
            }
        }
        return null;
    }

    /**
     * 从末尾向前扫描，找到保留的起始索引。
     * <p>
     * 策略：从末尾向前扫描完整的 AssistantMessage + ToolResponseMessage 对，
     * 收集 {@code keepRounds} 对后停止。
     * 如果最后一个消息不是 ToolResponseMessage（即 LLM 本轮尚未返回工具结果），
     * 它本身也需要被保留。
     *
     * @param messages 消息列表
     * @return 保留的起始索引（包含）
     */
    private int findKeepStart(List<Message> messages) {
        int count = 0;
        int i = messages.size() - 1;

        // 如果最后一条不是 ToolResponseMessage（如刚添加的 AssistantMessage），先保留它
        if (!(messages.get(i) instanceof ToolResponseMessage)) {
            i--;
        }

        // 从后向前扫描完整的 (AssistantMessage + ToolResponseMessage) 对
        while (i >= 1 && count < keepRounds) {
            if (messages.get(i) instanceof ToolResponseMessage
                    && i - 1 >= 1
                    && messages.get(i - 1) instanceof AssistantMessage) {
                count++;
                i -= 2; // 跳过这一对
            } else {
                break; // 遇到非标准结构，停止
            }
        }

        return i + 1; // 保留起始索引（包含）
    }

    // ==================== 并行工具执行 ====================

    /** 工具执行线程池（延迟初始化，JDK 21 虚拟线程，每工具一线程） */
    private volatile ExecutorService toolExecutor;

    /**
     * ⭐ 工具并发限流闸门。
     * <p>虚拟线程执行器是无界的，为保留原 {@code newFixedThreadPool(maxConcurrency)} 隐含的
     * “单批工具最多 maxConcurrency 个并行”语义（避免对外部工具 API / 搜索接口造成过高并发），
     * 用 {@link Semaphore} 维持该并发上限。与 toolExecutor 一同延迟初始化，以读取最终的 maxConcurrency。</p>
     */
    private volatile Semaphore toolConcurrencyLimiter;

    private ExecutorService getToolExecutor() {
        if (toolExecutor == null) {
            synchronized (this) {
                if (toolExecutor == null) {
                    // 限流闸门与执行器同步初始化（此时 maxConcurrency 已为最终值）
                    toolConcurrencyLimiter = new Semaphore(profile.maxConcurrency());
                    // I/O 阻塞型工具（搜索 / HTTP / 汇率等）：每次调用一根虚拟线程，阻塞期间释放载体线程
                    toolExecutor = Executors.newVirtualThreadPerTaskExecutor();
                }
            }
        }
        return toolExecutor;
    }

    /**
     * 执行工具调用列表（支持并行）。
     * <p>
     * 当工具数 > 1 且启用了并行执行时，使用 CompletableFuture 并行执行，
     * 结果按入参 {@code toolCalls} 的顺序收集，保证与 LLM 的对应关系。
     *
     * @param toolCalls LLM 返回的工具调用列表
     * @param toolMap   工具名到 ToolCallback 的映射
     * @return 按序排列的工具响应列表
     */
    private List<ToolResponseMessage.ToolResponse> executeTools(
            List<AssistantMessage.ToolCall> toolCalls,
            Map<String, ToolCallback> toolMap) {

        // 只有一个工具或关闭并行，串行执行
        if (!parallelExecution || toolCalls.size() <= 1) {
            return executeToolsSequential(toolCalls, toolMap);
        }

        log.info("[SmartReActAgent] 并行执行 {} 个工具 (最大并发 {})", toolCalls.size(), profile.maxConcurrency());

        // ⭐ 创建所有工具的异步任务
        List<CompletableFuture<ToolResponseMessage.ToolResponse>> futures = new ArrayList<>();
        for (var tc : toolCalls) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    // ⭐ 虚拟线程无界，用限流闸门保留“单批最多 maxConcurrency 个工具并行”的上限
                    toolConcurrencyLimiter.acquire();
                    try {
                        return executeToolCallWithRetry(tc, toolMap);
                    } finally {
                        toolConcurrencyLimiter.release();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(ie);
                }
            }, getToolExecutor()));
        }

        // ⭐ 等待所有工具完成（有超时）
        List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(profile.toolTimeoutMs(), TimeUnit.MILLISECONDS);

            // ⭐ 按入参顺序收集结果
            for (var f : futures) {
                results.add(f.get()); // 已完成的 future 立即返回
            }
        } catch (TimeoutException e) {
            log.warn("[SmartReActAgent] 工具批次超时 ({}ms)，已超时的工具将返回错误", profile.toolTimeoutMs());
            for (var f : futures) {
                if (f.isDone()) {
                    try { results.add(f.get()); }
                    catch (Exception ex) {
                        results.add(new ToolResponseMessage.ToolResponse("", "",
                                "{\"error_code\":\"TOOL_TIMEOUT\",\"message\":\"工具执行超时\",\"retryable\":true}"));
                    }
                } else {
                    f.cancel(true);
                    results.add(new ToolResponseMessage.ToolResponse("", "",
                            "{\"error_code\":\"TOOL_TIMEOUT\",\"message\":\"工具执行超时\",\"retryable\":true}"));
                }
            }
        } catch (Exception e) {
            log.error("[SmartReActAgent] 工具并行执行异常: {}", e.getMessage());
            // 降级到串行
            return executeToolsSequential(toolCalls, toolMap);
        }

        return results;
    }

    /**
     * 串行执行工具调用。
     */
    private List<ToolResponseMessage.ToolResponse> executeToolsSequential(
            List<AssistantMessage.ToolCall> toolCalls,
            Map<String, ToolCallback> toolMap) {

        List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
        for (var tc : toolCalls) {
            results.add(executeToolCallWithRetry(tc, toolMap));
        }
        return results;
    }

    /**
     * 执行单个工具调用并自动重试（基于 ErrorRecoveryService 表驱动决策）。
     * <p>
     * 工具返回的 JSON 结果如果包含 {@code error_code} 字段，会按 AgentErrorCode
     * 映射到恢复策略，决定是否自动重试。
     * </p>
     */
    private ToolResponseMessage.ToolResponse executeToolCallWithRetry(
            AssistantMessage.ToolCall tc, Map<String, ToolCallback> toolMap) {
        ToolCallback callback = toolMap.get(tc.name());
        if (callback == null) {
            log.warn("[SmartReActAgent] 未知工具: {}", tc.name());
            metrics.recordToolHallucination();
            recoveryService.logRecovery(AgentErrorCode.UNKNOWN_TOOL, RecoveryAction.CLARIFY_USER,
                    "tool=" + tc.name(), 0);
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
                log.info("[SmartReActAgent] {}执行工具: {} (id={}, attempt={})",
                        parallelExecution ? "并行" : "串行", tc.name(), tc.id(), attempt);
                long toolStart = System.currentTimeMillis();
                String result = callback.call(tc.arguments());
                long elapsed = System.currentTimeMillis() - toolStart;
                log.info("[SmartReActAgent] 工具 {} 完成 (耗时 {}ms)", tc.name(), elapsed);

                // ⭐ 检查工具返回结果是否包含 error_code
                String errorCode = extractErrorCode(result);
                if (errorCode == null) {
                    // 正常结果，先截断再返回
                    String truncatedResult = truncateToolResult(result, tc.name());
                    return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                            truncatedResult != null ? truncatedResult : "null");
                }

                // 工具返回了错误码，判断是否需要重试
                lastErrorCode = errorCode;
                AgentErrorCode agentCode = AgentErrorCode.fromCode(errorCode);
                if (agentCode != null && recoveryService.shouldRetry(agentCode, attempt)) {
                    long delay = recoveryService.getRetryDelayMs(agentCode, attempt - 1);
                    log.warn("[SmartReActAgent] 工具返回错误(第{}次): tool={}, code={}, {}ms后重试",
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

                // 不可重试或已达上限，返回原始错误
                log.warn("[SmartReActAgent] 工具返回不可重试错误(尝试{}次): tool={}, code={}",
                        attempt, tc.name(), errorCode);
                return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result);

            } catch (AgentException e) {
                // 标准化的工具异常
                lastErrorCode = e.getErrorCode().getCode();
                if (recoveryService.shouldRetry(e.getErrorCode(), attempt)) {
                    long delay = recoveryService.getRetryDelayMs(e.getErrorCode(), attempt - 1);
                    log.warn("[SmartReActAgent] 工具异常(第{}次): tool={}, code={}, {}ms后重试",
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
                return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), e.toToolResultJson());

            } catch (Exception e) {
                // 普通异常（工具抛出非AgentException的异常）
                lastException = e;
                AgentErrorCode code = AgentErrorCode.TOOL_EXECUTION_ERROR;
                if (recoveryService.shouldRetry(code, attempt)) {
                    long delay = recoveryService.getRetryDelayMs(code, attempt - 1);
                    log.warn("[SmartReActAgent] 工具异常(第{}次): tool={}, {}ms后重试",
                            attempt, tc.name(), delay);
                    try { Thread.sleep(delay); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                log.error("[SmartReActAgent] 工具执行失败(最后一次尝试): {} - {}", tc.name(), e.getMessage());
                return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                        "{\"error_code\":\"TOOL_EXECUTION_ERROR\",\"message\":\""
                                + e.getMessage() + "\",\"retryable\":true}");
            }
        }

        // 中断或重试耗尽
        String reason = lastErrorCode != null
                ? "工具返回错误: " + lastErrorCode
                : (lastException != null ? lastException.getMessage() : "工具执行失败");
        log.error("[SmartReActAgent] 工具执行失败(重试{}次): tool={}, reason={}", attempt - 1, tc.name(), reason);
        return new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                "{\"error_code\":\"TOOL_EXECUTION_ERROR\",\"message\":\""
                        + reason + "\",\"retryable\":false}");
    }

    /**
     * 工具参数的稳定指纹（FNV-1a 64-bit）。
     * <p>用于「同工具同参数」去重检测：相同参数序列得到相同指纹，
     * 不同参数序列（即使仅差一个字符）得到不同指纹，从而精确识别重放调用，
     * 避免接口故障时同一请求被反复重放形成调用风暴。</p>
     */
    private static long argHash64(String args) {
        if (args == null) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < args.length(); i++) {
            hash ^= (args.charAt(i) & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * 从工具返回的 JSON 结果中提取 error_code 字段值。
     * 仅检查格式为 {"error_code":"CODE",...} 的 JSON 对象，不依赖 Jackson。
     */
    private String extractErrorCode(String result) {
        if (result == null || result.isBlank()) return null;
        String trimmed = result.trim();
        if (!trimmed.startsWith("{")) return null;
        int keyIdx = trimmed.indexOf("\"error_code\"");
        if (keyIdx < 0) return null;
        // 查找冒号后的第一个引号
        int startQuote = trimmed.indexOf('"', keyIdx + 12);
        if (startQuote < 0) return null;
        int endQuote = trimmed.indexOf('"', startQuote + 1);
        if (endQuote <= startQuote) return null;
        return trimmed.substring(startQuote + 1, endQuote);
    }

    /** 截断字符串到64字符（用于日志） */
    private static String truncate64(String str) {
        if (str == null) return "";
        return str.length() > 64 ? str.substring(0, 64) + "..." : str;
    }

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 工具输出截断（保头保尾砍中间）
    // 参考文章二 "L1 实时截断"：头部保留结构信息，尾部保留结论，
    // 中间的大量数据行被压缩，避免 100K token 的工具输出撑爆上下文。
    // ═══════════════════════════════════════════════════════════════

    /**
     * 截断工具输出——保头保尾砍中间。
     * <p>
     * 只截输出不截输入（tool arguments 是模型生成的，tool output 是外部系统返回的）。
     * 可按工具类型配置不同截断策略。
     * </p>
     *
     * @param result   原始工具输出
     * @param toolName 工具名称（用于日志，未来可按工具类型配置策略）
     * @return 截断后的工具输出
     */
    private String truncateToolResult(String result, String toolName) {
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

        log.info("[SmartReActAgent] 工具输出截断: tool={}, original={} chars → {} chars (省略 {} chars)",
                toolName, totalLen, truncated.length(), omitted);

        return truncated;
    }

    /**
     * ⭐ 向自定义 ChatModel 注入工具列表（通过反射，避免循环依赖）。
     * <p>
     * 如果 chatModel 有 {@code setToolCallbacks(List)} 方法，则调用它。
     * 这允许 CustomDeepSeekChatModel 获取工具定义并向 DeepSeek API 发送 tools 参数。
     * </p>
     */
    private void injectToolsToModel(List<ToolCallback> tools) {
        try {
            var method = chatModel.getClass().getMethod("setToolCallbacks", java.util.List.class);
            method.invoke(chatModel, tools);
            log.debug("[SmartReActAgent] 通过反射注入 {} 个工具到 ChatModel", tools.size());
        } catch (NoSuchMethodException e) {
            // ChatModel 没有 setToolCallbacks 方法，这是正常的
        } catch (Exception e) {
            log.warn("[SmartReActAgent] 注入工具到 ChatModel 失败: {}", e.getMessage());
        }
    }
}
