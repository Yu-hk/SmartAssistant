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
import com.example.smartassistant.common.observability.OpsMetrics;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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
    /** 默认最大并发工具数 */
    private static final int DEFAULT_MAX_CONCURRENCY = 4;
    /** 默认单个工具超时（毫秒） */
    private static final long DEFAULT_TOOL_TIMEOUT_MS = 30_000;
    /** ⭐ 连续无增量检测阈值：连续几次工具调用结果相似时强制停止 */
    private static final int NO_INCREMENT_LIMIT = 2;

    // ═══════════════════════════════════════════════════════════════
    // ⭐ P1 Loop Engineering 常量（文章⑥确定性约束）
    // ═══════════════════════════════════════════════════════════════

    /** 无进展计数器阈值：连续几轮无实质进展时提前终止 */
    private static final int MAX_NO_PROGRESS_ITERATIONS = 3;

    /** 无进展检测：一次迭代中工具调用数少于此值视为"无实质进展" */
    private static final int MIN_PROGRESS_TOOL_CALLS = 1;

    // ═══════════════════════════════════════════════════════════════
    // ⭐ P2 决策状态机（文章⑥ 10 条优先级链路）
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 双阈值压缩常量（Scoped + Full Window）
    // ═══════════════════════════════════════════════════════════════

    /** Scoped 阈值：窗口净增长超过此比例时触发压缩 */
    private static final double SCOPED_RATIO = 0.9;

    /** Full Window 阈值：总 token 数超过此比例时强制压缩 */
    private static final double FULL_WINDOW_RATIO = 0.95;

    /** 压缩后重置的 prefill 基线（首次压缩后设为摘要占据的 token） */
    private int prefillBaseline = 0;

    // ═══════════════════════════════════════════════════════════════
    // ⭐ P1 Loop Engineering（文章⑥确定性约束组件）
    // ═══════════════════════════════════════════════════════════════

    /** 是否启用 Pre-AL Gate 注入（默认启用） */
    private boolean preALGateEnabled = true;

    /** 循环守卫服务：确定性快速判断 */
    private final LoopGuardService loopGuard = new LoopGuardService();

    /** Phase Gate：代码级验收门禁（可选，null 时跳过） */
    private PhaseGate phaseGate;

    /** 当前阶段的验收检查项列表（可选，null 时跳过 Phase Gate） */
    private java.util.List<PhaseGate.Check> phaseChecks;

    /** 当前阶段名称（可选，用于 Pre-AL Gate） */
    private String currentPhase;

    /** 反馈学习日志（可选，null 时跳过 per-round 结构化反馈记录） */
    private FeedbackLog feedbackLog;

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

    /** ⭐ T2d：会话级动态工具集（由 {@link #registerDiscoveredTool(ToolCallback...)} 注入，每轮合并到 effectiveTools） */
	private final List<ToolCallback> dynamicTools = new ArrayList<>();

	/** ⭐ T2d：已发现的能力历史（capabilityQuery → true），供护栏去重 */
	private final Map<String, Boolean> discoveredCapabilityHistory = new ConcurrentHashMap<>();

    /** ⭐ 可选的指标采集器 */
    private AgentMetricsCollector metrics = new AgentMetricsCollector() {};

    /** ⭐ G4 运营指标收集器（工具失败率 / 单轮 Token），零装配、全局注册表 */
    private final OpsMetrics opsMetrics = new OpsMetrics();

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

    /** ⭐ 对话摘要持久化存储（可选） */
    private ConversationSummaryStore summaryStore;
    /** ⭐ 上下文压缩器 */
    private ContextCompressor contextCompressor;
    /** ⭐ 工具执行器（延迟初始化） */
    private volatile AgentToolExecutor agentToolExecutor;

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
	 * 运行时刷新工具列表。
	 * <p>
	 * 当 Registry 中的工具定义发生变更（新增/废弃/状态变化）时，调用此方法
	 * 更新 Agent 运行时的工具列表，无需重启服务。
	 * </p>
	 * <p>
	 * 使用场景：Agent 已通过 {@link #withPreset(String, List)} 初始化，
	 * 但后续工具在 Registry 中注册或废弃。配合
	 * {@link com.example.smartassistant.common.tool.client.ToolRegistryClient#getToolCallbacks(String, Object...)}
	 * 实现热更新。
	 * </p>
	 *
	 * @param newTools 新的工具回调列表
	 * @return this
	 */
	public SmartReActAgent refreshTools(List<ToolCallback> newTools) {
		this.presetTools = newTools;
		return this;
	}

	// ═══════════════════════════════════════════════════════════════
	// ⭐ T2d：动态工具管理
	// ═══════════════════════════════════════════════════════════════

	/**
	 * 注册发现工具到会话级动态工具集（去重，同名覆盖）。
	 * <p>
	 * 当 {@link com.example.smartassistant.common.gateway.tool.meta.DiscoverToolsTool}
	 * 发现新工具后，调用此方法将其注入当前 Agent 的可用工具集。
	 * 同名工具已存在时覆盖（允许热更新工具定义）。
	 * </p>
	 *
	 * @param tcs 要注册的工具回调（可变参数）
	 * @return this
	 */
	public SmartReActAgent registerDiscoveredTool(ToolCallback... tcs) {
		if (tcs == null) return this;
		synchronized (dynamicTools) {
			for (ToolCallback tc : tcs) {
				if (tc == null) continue;
				String name = tc.getToolDefinition().name();
				// 同名覆盖：移除旧的回调（如已有相同名称的工具）
				dynamicTools.removeIf(existing ->
						existing.getToolDefinition().name().equals(name));
				dynamicTools.add(tc);
				log.info("[SmartReActAgent] 注册动态工具: {}", name);
			}
		}
		return this;
	}

	/**
	 * 记录已发现的能力（供护栏去重）。
	 *
	 * @param capabilityQuery 能力查询词
	 */
	public void recordDiscoveredCapability(String capabilityQuery) {
		if (capabilityQuery != null) {
			discoveredCapabilityHistory.put(capabilityQuery, Boolean.TRUE);
		}
	}

	/**
	 * 获取已发现的能力历史（供护栏去重检查）。
	 *
	 * @return 已发现的能力名集合
	 */
	public Set<String> getDiscoveredCapabilityHistory() {
		return new HashSet<>(discoveredCapabilityHistory.keySet());
	}

	/**
	 * 获取工具执行器（延迟初始化）。
	 */
	private AgentToolExecutor getAgentToolExecutor() {
		if (agentToolExecutor == null) {
			synchronized (this) {
				if (agentToolExecutor == null) {
					agentToolExecutor = new AgentToolExecutor(profile, metrics, recoveryService,
							opsMetrics, parallelExecution);
				}
			}
		}
		return agentToolExecutor;
	}

	/**
	 * 合并基础工具列表与动态工具列表（按工具名去重）。
	 * <p>动态工具同名覆盖基础工具。</p>
	 *
	 * @param base 基础工具列表（presetTools 或 ToolGroup active）
	 * @return 合并后的工具列表（base 在前，dynamicTools 去重追加）
	 */
	private List<ToolCallback> mergeWithDynamicTools(List<ToolCallback> base) {
		if (dynamicTools.isEmpty()) {
			return base;
		}
		// 收集动态工具名集合（用于去重检查）
		Set<String> dynamicNames = dynamicTools.stream()
				.map(tc -> tc.getToolDefinition().name())
				.collect(Collectors.toSet());
		// 过滤掉 base 中已被动态工具覆盖的同名项
		List<ToolCallback> merged = new ArrayList<>(base.size() + dynamicTools.size());
		for (ToolCallback tc : base) {
			if (!dynamicNames.contains(tc.getToolDefinition().name())) {
				merged.add(tc);
			}
		}
		merged.addAll(dynamicTools);
		return merged;
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

    // ═══════════════════════════════════════════════════════════════
    // ⭐ P1 Loop Engineering 配置方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 配置 Pre-AL Gate 注入（默认启用）。
     *
     * @param enabled 是否启用
     * @return this
     */
    public SmartReActAgent withPreALGate(boolean enabled) {
        this.preALGateEnabled = enabled;
        return this;
    }

    /**
     * 配置 Phase Gate 验收门禁。
     *
     * @param checks   验收检查项列表
     * @param phase    当前阶段名称
     * @return this
     */
    public SmartReActAgent withPhaseGate(java.util.List<PhaseGate.Check> checks, String phase) {
        this.phaseGate = new PhaseGate();
        this.phaseChecks = checks;
        this.currentPhase = phase;
        return this;
    }

    /**
     * 配置反馈学习日志。
     *
     * @param feedbackLog 反馈日志实例
     * @return this
     */
    public SmartReActAgent withFeedbackLog(FeedbackLog feedbackLog) {
        this.feedbackLog = feedbackLog;
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

		// ⭐ T2d：合并动态工具（去重，同名覆盖）
		effectiveTools = mergeWithDynamicTools(effectiveTools);
		if (!dynamicTools.isEmpty()) {
			log.info("[SmartReActAgent] 合并动态工具: preset={}, dynamic={}, total={}",
					effectiveTools.size() - dynamicTools.size(), dynamicTools.size(), effectiveTools.size());
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

        // ⭐ P1 无进展计数器
        int consecutiveParseFailures = 0;
        int noProgressCount = 0;
        String lastToolContextHash = null;

        while (iteration < profile.maxIterations()) {
            iteration++;
            long elapsed = System.currentTimeMillis() - startTime;

            // ⭐ 超时检查
            if (elapsed > profile.timeoutMs()) {
                log.warn("[SmartReActAgent] ⏰ 超时 ({}ms, 迭代 {} 次)", elapsed, iteration);
                metrics.recordTimeout();
                if (feedbackLog != null) {
                    feedbackLog.recordFailure(iteration, "超时",
                            "缩短单轮执行时间或增大超时阈值",
                            "尝试拆解为多个子任务并行执行");
                }
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
                    if (contextCompressor == null) {
                        contextCompressor = new ContextCompressor(chatModel, profile, summaryStore, summaryChain);
                    }
                    compressed = contextCompressor.compress(messages);
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

            // ═══════════════════════════════════════════════════════════
            // ⭐ P1 Pre-AL Gate：每轮注入结构化执行契约
            // ═══════════════════════════════════════════════════════════
            if (preALGateEnabled) {
                String preALText = new PreALGate(iteration, profile.maxIterations(),
                        currentPhase, pendingCheckNames()).render();
                // 找到 system message（通常是 messages[0]）并追加契约文本
                if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage sysMsg) {
                    messages.set(0, new SystemMessage(sysMsg.getText() + preALText));
                }
            }

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
                // ⭐ P1-4 连续解析失败保护：评估器连续无效→暂停避免烧预算
                consecutiveParseFailures++;
                if (consecutiveParseFailures >= AgentLoopDecision.MAX_PARSE_FAILURES) {
                    log.warn("[SmartReActAgent] 连续 {} 次解析失败，暂停避免烧预算", consecutiveParseFailures);
                    metrics.recordMaxIterationHit();
                    return recoveryService.resolveUserMessage(AgentErrorCode.SYSTEM_MAX_ITERATIONS,
                            "连续" + consecutiveParseFailures + "次LLM返回空，已暂停");
                }
                continue;
            }
            // 成功获取结果，重置计数器
            consecutiveParseFailures = 0;

            // ⭐ G4 运营指标：单轮 Token 消耗（从 ChatResponse 用量元数据提取，非阻断）
            try {
                var usage = response.getMetadata().getUsage();
                if (usage != null && usage.getTotalTokens() != null) {
                    opsMetrics.recordTurnTokens("smart_react", usage.getTotalTokens());
                }
            } catch (Exception ignore) {
                // 指标采集失败不影响主流程
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
            String answerText = assistantMsg.getText();

            // ═══════════════════════════════════════════════════════════
            // ⭐ P1-2 确定性快速判断（LoopGuardService）：代码级检测三类状态
            // ═══════════════════════════════════════════════════════════
            if (answerText != null && !answerText.isBlank()) {
                var guardResult = loopGuard.analyze(answerText);
                if (!guardResult.isContinue()) {
                    log.warn("[SmartReActAgent] 🛑 循环守卫触发: action={}, reason={}",
                            guardResult.action(), guardResult.reason());
                    metrics.recordMaxIterationHit();
                    // 守卫暂停：返回包含暂停原因的友好消息
                    return switch (guardResult.action()) {
                        case PAUSE_BLOCKED -> "检测到 Agent 报告被阻塞，无法继续。请提供更多信息或重新描述需求。";
                        case AWAIT_CONFIRMATION -> "Agent 在等待您的确认。请根据提示做出选择。";
                        case PAUSE_INFRASTRUCTURE -> "检测到基础设施故障，已暂停以避免持续重试。请稍后再试。";
                        default -> "循环守卫暂停";
                    };
                }
            }

            // ⭐ 没有工具调用 → 最终回答
            if (toolCalls == null || toolCalls.isEmpty()) {
                // ⭐ P1-5 无进展检测：只有文本无工具调用且内容较短（非最终回答）
                // 使用工具调用次数与内容长度联合判定
                boolean hasProgress = answerText != null && answerText.length() > 50;
                if (!hasProgress) {
                    noProgressCount++;
                    if (noProgressCount >= MAX_NO_PROGRESS_ITERATIONS) {
                        log.warn("[SmartReActAgent] 连续 {} 轮无实质进展，提前终止", noProgressCount);
                        return recoveryService.resolveUserMessage(AgentErrorCode.SYSTEM_NO_INCREMENT,
                                "连续" + noProgressCount + "轮无实质进展，已终止");
                    }
                } else {
                    noProgressCount = 0;
                }
                String answer = answerText;
                log.info("[SmartReActAgent] 最终回答 (迭代 {} 轮, 耗时 {}ms, Token 输入={}, 输出={})",
                        iteration, elapsed, totalInputTokens, totalOutputTokens);
                if (feedbackLog != null) {
                    String progress = noProgressCount > 0 ? "low" : "high";
                    feedbackLog.recordProgress(iteration, progress);
                }
                return answer;
            }
            // 有工具调用 → 重置无进展计数器
            noProgressCount = 0;

            log.debug("[SmartReActAgent] 收到 {} 个工具调用", toolCalls.size());

            // ⭐ 将 assistant 的 tool_call 请求加入对话
            messages.add(assistantMsg);

            // ⭐ 执行工具（支持并行，带追踪跨度）
            List<ToolResponseMessage.ToolResponse> toolResponses = TraceSpan.of(observationRegistry, "agent-tool-execute")
                    .run(() -> getAgentToolExecutor().execute(toolCalls, toolMap));

            // ⭐ G1 连续无增量检测：防止重复调同类工具陷入循环。
            // 哈希键加入参数指纹 —— 仅"名称 + 参数完全一致"判定为无增量，
            // 实现文章《生产级 Agent 架构实战》要求的"同工具同参数去重"，
            // 避免接口故障时同一请求被反复重放形成调用风暴。
            String currentHash = toolCalls.stream()
                    .map(tc -> tc.name() + "(" + Long.toHexString(AgentToolExecutor.argHash64(tc.arguments())) + ")")
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
                if (contextCompressor == null) {
                    contextCompressor = new ContextCompressor(chatModel, profile, summaryStore, summaryChain);
                }
                precomputedCompactFuture = CompletableFuture.supplyAsync(() -> contextCompressor.compress(snapshot));
            }

            // ═══════════════════════════════════════════════════════════
            // ⭐ P2 决策状态机：10 条优先级链路日志
            // ═══════════════════════════════════════════════════════════
            AgentLoopDecision.LoopAction nextAction = AgentLoopDecision.decide(new AgentLoopDecision.DecisionContext(
                    iteration, false, "", LoopGuardService.GuardAction.CONTINUE,
                    consecutiveParseFailures, noProgressCount),
                    phaseChecks != null && !phaseChecks.isEmpty(),
                    profile.maxIterations());
            if (nextAction == AgentLoopDecision.LoopAction.STRATEGY_SWITCH) {
                log.warn("[SmartReActAgent] ⚡ 策略切换触发: 无进展{}轮，考虑更换Agent/策略", noProgressCount);
            } else {
                log.debug("[SmartReActAgent] 决策状态机: iteration={}, action={}", iteration, nextAction);
            }

            // 继续循环
        }

        // ⭐ 达到最大迭代次数
        log.warn("[SmartReActAgent] 达到最大迭代次数 {}", profile.maxIterations());
        metrics.recordMaxIterationHit();
        if (feedbackLog != null) {
            feedbackLog.recordFailure(profile.maxIterations(), "达到迭代上限",
                    "增大 maxIterations 或检查 Agent 是否陷入死循环",
                    "尝试委派子Agent或将大任务拆解为多个小任务");
        }
        recoveryService.logRecovery(AgentErrorCode.SYSTEM_MAX_ITERATIONS, RecoveryAction.FALLBACK_AGENT,
                "maxIterations=" + profile.maxIterations(), profile.maxIterations());
        return recoveryService.resolveUserMessage(AgentErrorCode.SYSTEM_MAX_ITERATIONS, null);
    }
    private java.util.List<String> pendingCheckNames() {
        if (phaseChecks == null) return java.util.List.of();
        return phaseChecks.stream()
                .filter(c -> c != null)
                .map(PhaseGate.Check::description)
                .collect(java.util.stream.Collectors.toList());
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
