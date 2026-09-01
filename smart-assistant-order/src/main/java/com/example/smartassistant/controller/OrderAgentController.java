/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.TokenUsageHeaders;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.audit.ToolUsageHeaders;
import com.example.smartassistant.common.memory.ContextOrchestrator;
import com.example.smartassistant.common.memory.MemoryExtractor;
import com.example.smartassistant.common.quality.DomainAgentResponse;
import com.example.smartassistant.common.quality.DomainQualityHeaders;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.trace.RagStage;
import com.example.smartassistant.common.rag.trace.StageSpan;
import com.example.smartassistant.common.observability.OpsMetrics;
import com.example.smartassistant.common.rag.eval.FaithfulnessGuard;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.common.tool.ToolLogContext;
import com.example.smartassistant.service.core.OrderIntentService;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import com.example.smartassistant.service.core.OrderDeterministicExecutionService;
import com.example.smartassistant.service.core.OrderRagService;
import com.example.smartassistant.service.quality.OrderDomainQualityValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Agent HTTP 直调控制器
 * <p>
 * 替代 A2A 协议，为 Router 提供直接的 HTTP 调用入口。
 * Router 不再需要经过 A2aRemoteAgent，直接 POST 到此端点即可获取 Agent 处理结果。
 * </p>
 * <p>
 * <b>改进</b>：加入意图检测 + RAG 预检索流程：
 * <ol>
 *   <li>识别用户意图（下单/查询/退款/取消/其他）</li>
 *   <li>根据意图预检索相关数据（如预查订单信息）</li>
 *   <li>将检索结果注入上下文后交给 Agent 执行</li>
 * </ol>
 * </p>
 */
@RestController
@RequestMapping({"/api/order/agent", "/internal/agents/order"})
public class OrderAgentController {

    private static final Logger log = LoggerFactory.getLogger(OrderAgentController.class);

    private final SmartReActAgent orderAgent;
    private final OrderIntentService intentService;
    private final OrderRagService ragService;
    /** 记忆后台提取器：对话结束后自动提取用户偏好 */
    private final MemoryExtractor memoryExtractor;
    /** 上下文协调器：统一调度四层记忆预算 */
    private final ContextOrchestrator orchestrator;
    private final OrderDomainQualityValidator domainQualityValidator;
    private final OrderDeterministicExecutionService deterministicExecutionService;

    /** ⭐ P1 全阶段 trace 记录器（可选，null 时跳过 trace） */
    @Autowired(required = false)
    private StageTraceRecorder stageTraceRecorder;

    /** ⭐ P5-A 生产忠实度护栏（可选，默认内置实例；测试可注入定制实例） */
    private FaithfulnessGuard faithfulnessGuard = new FaithfulnessGuard();

    /** ⭐ G4 运营指标收集器（应答/无证据拒答），零装配、全局注册表 */
    private final OpsMetrics opsMetrics = new OpsMetrics();

    /** 测试/手动注入用 setter */
    public void setStageTraceRecorder(StageTraceRecorder stageTraceRecorder) {
        this.stageTraceRecorder = stageTraceRecorder;
    }

    /** 测试可注入定制 FaithfulnessGuard */
    public void setFaithfulnessGuard(FaithfulnessGuard faithfulnessGuard) {
        this.faithfulnessGuard = faithfulnessGuard;
    }

    public OrderAgentController(SmartReActAgent orderAgent,
                                OrderIntentService intentService,
                                OrderRagService ragService,
                                MemoryExtractor memoryExtractor,
                                ContextOrchestrator orchestrator) {
        this(orderAgent, intentService, ragService, memoryExtractor, orchestrator,
                new OrderDomainQualityValidator(), null);
    }

    public OrderAgentController(SmartReActAgent orderAgent,
                                OrderIntentService intentService,
                                OrderRagService ragService,
                                MemoryExtractor memoryExtractor,
                                ContextOrchestrator orchestrator,
                                OrderDomainQualityValidator domainQualityValidator) {
        this(orderAgent, intentService, ragService, memoryExtractor, orchestrator,
                domainQualityValidator, null);
    }

    @Autowired
    public OrderAgentController(SmartReActAgent orderAgent,
                                OrderIntentService intentService,
                                OrderRagService ragService,
                                MemoryExtractor memoryExtractor,
                                ContextOrchestrator orchestrator,
                                OrderDomainQualityValidator domainQualityValidator,
                                OrderDeterministicExecutionService deterministicExecutionService) {
        this.orderAgent = orderAgent;
        this.intentService = intentService;
        this.ragService = ragService;
        this.memoryExtractor = memoryExtractor;
        this.orchestrator = orchestrator;
        this.domainQualityValidator = domainQualityValidator;
        this.deterministicExecutionService = deterministicExecutionService;
    }

    /**
     * 处理用户问题并返回 Agent 响应。
     * <p>
     * 流程：
     * <ol>
     *   <li>LLM 意图识别（下单/查询/退款/取消）</li>
     *   <li>根据意图 RAG 预检索（如预查订单信息）</li>
     *   <li>将检索结果注入上下文</li>
     *   <li>交给 Agent 执行并返回</li>
     * </ol>
     * </p>
     *
     * @param request 请求体，包含 question 字段
     * @return Agent 执行结果
     */
    @PostMapping("/process")
    public ResponseEntity<String> processQuestionHttp(@RequestBody Map<String, String> request) {
        String requestId = request.get("requestId");
        ToolUsageCache.start(requestId);
        DomainAgentResponse response = processQuestionWithQuality(request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(DomainQualityHeaders.STATUS, response.quality().getStatus().name())
                .header(DomainQualityHeaders.SCORE, String.valueOf(response.quality().getScore()))
                .header(DomainQualityHeaders.REASON_CODES, response.quality().reasonCodesHeaderValue());
        TokenUsageCache.TokenUsage usage = TokenUsageCache.consume(requestId);
        if (usage != null) {
            if (usage.promptTokens() != null) {
                builder.header(TokenUsageHeaders.PROMPT_TOKENS, String.valueOf(usage.promptTokens()));
            }
            if (usage.completionTokens() != null) {
                builder.header(TokenUsageHeaders.COMPLETION_TOKENS, String.valueOf(usage.completionTokens()));
            }
            if (usage.totalTokens() != null) {
                builder.header(TokenUsageHeaders.TOTAL_TOKENS, String.valueOf(usage.totalTokens()));
            }
        }
        String toolUsage = ToolUsageHeaders.encode(ToolUsageCache.consume(requestId));
        if (toolUsage != null) builder.header(ToolUsageHeaders.TOOL_USAGE, toolUsage);
        return builder.body(response.answer());
    }

    /** Unified Router-to-Agent protocol; legacy /process remains available during migration. */
    @PostMapping("/execute")
    public ResponseEntity<AgentExecutionResponse> execute(
            @RequestBody AgentExecutionRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String headerRequestId) {
        String requestId = headerRequestId != null ? headerRequestId : request.executionId();
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(
                    AgentExecutionResponse.failure("EMPTY_ORDER_QUESTION",
                            "Question must not be blank", false));
        }

        if (deterministicExecutionService != null
                && deterministicExecutionService.supports(request.operation())) {
            long startedAt = System.currentTimeMillis();
            ToolUsageCache.start(requestId);
            ToolLogContext.setRequestId(requestId);
            ToolLogContext.setIdempotencyKey(request.idempotencyKey());
            try {
                AgentExecutionResponse response = deterministicExecutionService.execute(request);
                log.info("[OrderFastPath] 统一协议快速执行完成: operation={}, requestId={}, status={}, elapsed={}ms",
                        request.operation(), requestId, response.status(),
                        System.currentTimeMillis() - startedAt);
                return typedResponse(response, requestId);
            } finally {
                ToolLogContext.clear();
            }
        }

        Map<String, String> legacyRequest = new java.util.LinkedHashMap<>();
        legacyRequest.put("question", request.question());
        if (request.userId() != null) legacyRequest.put("userId", request.userId());
        if (requestId != null) legacyRequest.put("requestId", requestId);

        ToolUsageCache.start(requestId);
        DomainAgentResponse response;
        ToolLogContext.setRequestId(requestId);
        ToolLogContext.setIdempotencyKey(request.idempotencyKey());
        try {
            response = processQuestionWithQuality(legacyRequest);
        } finally {
            ToolLogContext.clear();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(DomainQualityHeaders.STATUS, response.quality().getStatus().name())
                .header(DomainQualityHeaders.SCORE, String.valueOf(response.quality().getScore()))
                .header(DomainQualityHeaders.REASON_CODES, response.quality().reasonCodesHeaderValue());
        TokenUsageCache.TokenUsage usage = TokenUsageCache.consume(requestId);
        if (usage != null) {
            if (usage.promptTokens() != null) builder.header(
                    TokenUsageHeaders.PROMPT_TOKENS, String.valueOf(usage.promptTokens()));
            if (usage.completionTokens() != null) builder.header(
                    TokenUsageHeaders.COMPLETION_TOKENS, String.valueOf(usage.completionTokens()));
            if (usage.totalTokens() != null) builder.header(
                    TokenUsageHeaders.TOTAL_TOKENS, String.valueOf(usage.totalTokens()));
        }
        String toolUsage = ToolUsageHeaders.encode(ToolUsageCache.consume(requestId));
        if (toolUsage != null) builder.header(ToolUsageHeaders.TOOL_USAGE, toolUsage);
        return builder.body(AgentExecutionResponse.success(response.answer(), response.quality()));
    }

    private ResponseEntity<AgentExecutionResponse> typedResponse(
            AgentExecutionResponse response, String requestId) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (response.quality() != null) {
            builder.header(DomainQualityHeaders.STATUS, response.quality().status())
                    .header(DomainQualityHeaders.SCORE, String.valueOf(response.quality().score()))
                    .header(DomainQualityHeaders.REASON_CODES,
                            String.join(",", response.quality().reasonCodes()));
        }
        TokenUsageCache.TokenUsage usage = TokenUsageCache.consume(requestId);
        if (usage != null) {
            if (usage.promptTokens() != null) builder.header(
                    TokenUsageHeaders.PROMPT_TOKENS, String.valueOf(usage.promptTokens()));
            if (usage.completionTokens() != null) builder.header(
                    TokenUsageHeaders.COMPLETION_TOKENS, String.valueOf(usage.completionTokens()));
            if (usage.totalTokens() != null) builder.header(
                    TokenUsageHeaders.TOTAL_TOKENS, String.valueOf(usage.totalTokens()));
        }
        String toolUsage = ToolUsageHeaders.encode(ToolUsageCache.consume(requestId));
        if (toolUsage != null) builder.header(ToolUsageHeaders.TOOL_USAGE, toolUsage);
        return builder.body(response);
    }

    /** Backward-compatible entry point used by local callers and unit tests. */
    public String processQuestion(Map<String, String> request) {
        return processQuestionWithQuality(request).answer();
    }

    /** Executes the order Agent and returns its domain-owned quality decision. */
    public DomainAgentResponse processQuestionWithQuality(Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return DomainAgentResponse.of("❌ 问题不能为空",
                    DomainQualityResult.fail("EMPTY_ORDER_QUESTION"));
        }

        long startTime = System.currentTimeMillis();
        // ⭐ P1 全阶段 trace：使用真实 requestId 串联（Router 下发或本地生成）
        String requestId = request.getOrDefault("requestId", "ord-" + System.nanoTime());
        log.info("[OrderAgent] 收到请求: question={}, requestId={}", question, requestId);

        try {
            // Step 1: 意图识别
            IntentType intent = intentService.detect(question);
            // ⭐ G4 运营指标：记录一次订单域应答（无答案率分母）
            opsMetrics.recordAnswer("order", intent.getLabel());

            // Router 的统一协议通常会直接进入确定性快速路径；兼容入口收到
            // 清晰的订单/物流只读查询时也必须复用同一路径，避免再次依赖
            // RAG 或生成模型。读取由订单域校验 userId/订单归属，写操作不在此降级。
            DomainAgentResponse deterministicRead = executeDeterministicRead(
                    intent, question, request.get("userId"), requestId);
            if (deterministicRead != null) {
                return deterministicRead;
            }

            // Step 2: 上下文协调器 + RAG 预检索（含质量评估）
            String userId = request.get("userId");
            List<String> extras = new ArrayList<>();

            // ⭐ P1: 先取检索质量结果，决定是否"无证据拒答"
            long retrievalStart = System.currentTimeMillis();
            RetrievalQualityResult qr = (ragService != null)
                    ? ragService.retrieveWithQualityResult(intent, question)
                    : RetrievalQualityResult.highQuality("", 1.0);
            long retrievalMs = System.currentTimeMillis() - retrievalStart;

            if (qr.isRejected()) {
                // ⭐ 无证据：短路，绝不调用 LLM（避免幻觉）
                if (stageTraceRecorder != null) {
                    stageTraceRecorder.getOrCreate(requestId, question, "order_agent")
                            .addStage(StageSpan.of(RagStage.RETRIEVAL, retrievalMs, StageSpan.STATUS_OK,
                                    Map.of("qualityScore", qr.getNormalizedScore(),
                                            "rejectionCode", qr.getRejectionCode())));
                    stageTraceRecorder.markRejection(requestId, qr.getRejectionCode(), qr.getRejectionMessage());
                    stageTraceRecorder.recordStage(requestId, RagStage.GENERATION, StageSpan.STATUS_SKIPPED, 0,
                            Map.of("reason", "no-evidence"));
                    stageTraceRecorder.save(requestId);
                }
                log.info("[OrderAgent] ⛔ 无证据拒答: intent={}, code={}, requestId={}",
                        intent.getLabel(), qr.getRejectionCode(), requestId);
                // ⭐ G4 运营指标：记录无证据拒答
                opsMetrics.recordNoEvidenceAnswer("order", intent.getLabel());
                return DomainAgentResponse.of(qr.getRejectionMessage(),
                        domainQualityValidator.evaluate(
                                question, qr.getRejectionMessage(), intent, userId, qr, null));
            }

            // Public refund/return policy is a read-only knowledge answer. Short-circuit the
            // action-oriented ReAct loop so words such as "请选择退款原因" in policy documents
            // cannot be mistaken for a pending order-operation confirmation.
            if (intent == IntentType.REFUND_POLICY
                    || intent == IntentType.ORDER_PREPARATION_GUIDANCE
                    || intent == IntentType.ORDER_GUIDANCE) {
                String result = switch (intent) {
                    case REFUND_POLICY -> ragService.buildRefundPolicyAnswer(qr);
                    case ORDER_PREPARATION_GUIDANCE, ORDER_GUIDANCE ->
                            ragService.buildOrderGuidanceAnswer(qr);
                    default -> throw new IllegalStateException("Unexpected read-only intent: " + intent);
                };
                if (stageTraceRecorder != null) {
                    stageTraceRecorder.getOrCreate(requestId, question, "order_agent")
                            .addStage(StageSpan.of(RagStage.RETRIEVAL, retrievalMs, StageSpan.STATUS_OK,
                                    Map.of("qualityScore", qr.getNormalizedScore(),
                                            "highQuality", qr.isHighQuality())));
                    stageTraceRecorder.recordStage(requestId, RagStage.GENERATION,
                            StageSpan.STATUS_SKIPPED, 0, Map.of("reason",
                        intent == IntentType.REFUND_POLICY
                                ? "public-policy-answer" : "order-guidance-answer"));
                    stageTraceRecorder.save(requestId);
                }
                DomainQualityResult quality = domainQualityValidator.evaluate(
                        question, result, intent, userId, qr, null);
                if (result != null && userId != null && !userId.isBlank() && !"null".equals(userId)) {
                    final String finalResult = result;
                    CompletableFuture.runAsync(() ->
                            memoryExtractor.extractFromConversation("order", userId, question, finalResult));
                }
                log.info("[OrderAgent] 只读订单知识已直接返回: intent={}, requestId={}, quality={}",
                        intent.getLabel(), requestId, quality.getStatus());
                return DomainAgentResponse.of(result, quality);
            }

            // 有证据：注入上下文
            String ragContext = ragService.buildEnhancedMessage(qr, question);
            if (!ragContext.equals(question)) {
                extras.add(ragContext);
                log.info("[OrderAgent] RAG 预检索已注入上下文");
            }

            // 通过 Orchestrator 构建分层 prompt
            String enhancedQuestion = orchestrator.buildPrompt(question, userId, "order",
                    extras.isEmpty() ? null : extras);
            log.info("[OrderAgent] 状态锚点已强制注入: userId={}", userId != null ? userId : "访客");

            // Step 4: Agent 执行（GENERATION 阶段 trace）
            log.info("[OrderAgent] 意图识别: {}, userId={}, 记忆注入={}", intent.getLabel(), userId, userId != null);
            long genStart = System.currentTimeMillis();
            String result = null;
            FaithfulnessGuard.FaithfulnessVerdict faithfulness = null;
            String genStatus = StageSpan.STATUS_OK;
            try {
                result = orderAgent.execute(enhancedQuestion);
                // ⭐ P5-A 生产 Faithfulness 校验（文章Q⑩校验层）：
                // 回答关键断言未被检索上下文支撑时，非阻断地追加免责声明 + 埋点（log）
                if (qr != null && qr.getContent() != null && !qr.getContent().isBlank()) {
                    faithfulness = faithfulnessGuard.check(result, qr.getContent());
                    if (faithfulness.hallucination()) {
                        log.warn("[OrderAgent] ⚠️ Faithfulness 风险: score={}, claims={}",
                                String.format("%.2f", faithfulness.score()), faithfulness.claims().size());
                    }
                }
            } catch (Exception e) {
                genStatus = StageSpan.STATUS_ERROR;
                throw e;
            } finally {
                long genMs = System.currentTimeMillis() - genStart;
                if (stageTraceRecorder != null) {
                    stageTraceRecorder.getOrCreate(requestId, question, "order_agent")
                            .addStage(StageSpan.of(RagStage.RETRIEVAL, retrievalMs, StageSpan.STATUS_OK,
                                    Map.of("qualityScore", qr.getNormalizedScore(),
                                            "highQuality", qr.isHighQuality())));
                    stageTraceRecorder.recordStage(requestId, RagStage.GENERATION, genStatus, genMs,
                            Map.of("outputLength", result != null ? result.length() : 0));
                    stageTraceRecorder.save(requestId);
                }
            }
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[OrderAgent] 处理完成: intent={},耗时={}ms,结果长度={}",
                    intent.getLabel(), elapsed, result != null ? result.length() : 0);

            DomainQualityResult quality = domainQualityValidator.evaluate(
                    question, result, intent, userId, qr, faithfulness);
            if (quality.isFail()) {
                result = "抱歉，订单信息校验未通过，请重新提供订单号或稍后重试。";
            }

            // Only persist an answer after the domain validator has accepted or safely downgraded it.
            if (result != null && userId != null && !userId.isBlank() && !"null".equals(userId)) {
                final String finalQuestion = question;
                final String finalResult = result;
                CompletableFuture.runAsync(() ->
                    memoryExtractor.extractFromConversation("order", userId, finalQuestion, finalResult));
            }
            return DomainAgentResponse.of(
                    result != null ? result : "⚠️ Agent 返回空结果", quality);

        } catch (Exception e) {
            log.error("[OrderAgent] 处理失败: {}", e.getMessage(), e);
            return DomainAgentResponse.of("❌ 处理失败: " + e.getMessage(),
                    DomainQualityResult.fail("ORDER_EXECUTION_ERROR"));
        }
    }

    private DomainAgentResponse executeDeterministicRead(
            IntentType intent, String question, String userId, String requestId) {
        if (deterministicExecutionService == null) return null;
        String operation = switch (intent) {
            case QUERY_ORDER -> "QUERY_ORDER";
            case TRACK_LOGISTICS -> "TRACK_LOGISTICS";
            default -> null;
        };
        if (operation == null || !deterministicExecutionService.supports(operation)) return null;

        AgentExecutionRequest deterministicRequest = new AgentExecutionRequest(
                AgentExecutionRequest.CURRENT_VERSION, requestId, "legacy-read", userId,
                operation, question, Map.of(), List.of(), List.of(), null, null);
        AgentExecutionResponse result = deterministicExecutionService.execute(deterministicRequest);
        DomainQualityResult quality = result.quality() != null
                ? result.quality().toDomainQuality()
                : DomainQualityResult.unknown();
        if (result.status() == AgentExecutionResponse.Status.SUCCEEDED) {
            log.info("[OrderFastPath] 兼容入口只读查询完成: operation={}, requestId={}",
                    operation, requestId);
            return DomainAgentResponse.of(result.answer(), quality);
        }
        String message = result.error() != null
                ? result.error().message() : "订单查询失败，请稍后重试。";
        return DomainAgentResponse.of(message, quality);
    }
}
