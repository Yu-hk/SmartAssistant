/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentExecutionResponse;
import com.example.smartassistant.common.agent.protocol.AgentNodeOutput;
import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.TokenUsageHeaders;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.audit.ToolUsageHeaders;
import com.example.smartassistant.common.quality.DomainAgentResponse;
import com.example.smartassistant.common.quality.DomainQualityHeaders;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.util.UserQuestionNormalizer;
import com.example.smartassistant.routing.contract.WorkflowOperation;
import com.example.smartassistant.service.agent.StreamingProductAgentService;
import com.example.smartassistant.service.core.ProductDiscoveryService;
import com.example.smartassistant.service.core.StructuredProductRecommendation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.smartassistant.service.core.ProductEvidenceResponsePolicy.*;

/**
 * Product 服务流式响应控制器
 * <p>
 * 提供 SSE 流式输出，实时展示 AI 推理过程
 * <p>
 * SSE 事件类型：
 * - event: thinking  - AI 思考过程
 * - event: tool_call - 工具调用请求
 * - event: tool_result - 工具执行结果
 * - event: response   - 最终回复
 * - event: done       - 完成信号
 */
@RestController
@RequestMapping({"/product/stream", "/internal/agents/product"})
@Slf4j
public class ProductStreamController {

    private final StreamingProductAgentService streamingAgentService;
    private final ProductDiscoveryService productDiscoveryService;

    @Autowired(required = false)
    private com.example.smartassistant.service.core.ProductFactQueryService factQueryService;

    public ProductStreamController(StreamingProductAgentService streamingAgentService) {
        this(streamingAgentService, null);
    }

    @Autowired
    public ProductStreamController(StreamingProductAgentService streamingAgentService,
                                   ProductDiscoveryService productDiscoveryService) {
        this.streamingAgentService = streamingAgentService;
        this.productDiscoveryService = productDiscoveryService;
    }

    /**
     * SSE 流式对话接口
     * <p>
     * 支持实时展示 AI 推理过程
     *
     * @param message      用户消息
     * @param showThinking 是否显示思考过程（默认 false，仅调试时显式开启）
     * @return SSE 事件流
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<org.springframework.http.codec.ServerSentEvent<String>> streamChat(
            @RequestParam String message,
            @RequestParam(required = false, defaultValue = "false") boolean showThinking,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {

        String normalizedMessage = UserQuestionNormalizer.normalize(message);
        log.info("[ProductStream] 开始流式对话: message={}, showThinking={}", normalizedMessage, showThinking);

        AtomicInteger step = new AtomicInteger(1);

        return Flux.create(sink -> {
            try {
                // 1. 发送 thinking 事件（模拟推理开始）
                if (showThinking) {
                    sink.next(createSSEEvent("thinking", step.getAndIncrement(), "正在分析用户需求..."));
                }

                // 2. 发送 tool_call 事件（模拟工具调用）
                sink.next(createSSEEvent("tool_call", step.getAndIncrement(), null, "queryProductInfo", null));

                // 3. 发送 tool_result 事件
                sink.next(createSSEEvent("tool_result", 0, "正在查询商品数据库..."));

                // 4. 执行实际推理
                String result = streamingAgentService.execute(normalizedMessage);

                // 5. 发送最终回复
                sink.next(createSSEEvent("response", 0, result));
                var usageEvent = createTokenUsageEvent(requestId);
                if (usageEvent != null) sink.next(usageEvent);

                // 6. 发送完成信号
                sink.next(createSSEEvent("done", 0, null));

                sink.complete();
                log.info("[ProductStream] 流式对话完成");

            } catch (Exception e) {
                var usageEvent = createTokenUsageEvent(requestId);
                if (usageEvent != null) sink.next(usageEvent);
                log.error("[ProductStream] 流式对话异常: {}", e.getMessage(), e);
                sink.next(createSSEEvent("error", 0, "处理失败: " + e.getMessage()));
                sink.next(createSSEEvent("done", 0, null));
                sink.complete();
            }
        });
    }

    /**
     * 简单的非流式对话（兼容旧接口）
     */
    @PostMapping("/chat/sync")
    public ResponseEntity<String> chatSync(
            @RequestParam String message,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        message = UserQuestionNormalizer.normalize(message);
        log.info("[ProductStream] 同步对话: {}", message);
        ToolUsageCache.start(requestId);
        DomainAgentResponse response = streamingAgentService.executeWithQuality(message, requestId);
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

    /** Unified Router-to-Agent protocol; legacy /chat/sync remains available during migration. */
    @PostMapping("/execute")
    public ResponseEntity<AgentExecutionResponse> execute(
            @RequestBody AgentExecutionRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String headerRequestId) {
        String requestId = headerRequestId != null ? headerRequestId : request.executionId();
        try {
            return executeRequest(request, requestId);
        } catch (com.example.smartassistant.spi.ProductCatalogUnavailableException e) {
            return executionResponse(requestId, AgentExecutionResponse.failure(
                    com.example.smartassistant.spi.ProductCatalogUnavailableException.CODE,
                    e.getMessage(), true), true);
        }
    }

    private ResponseEntity<AgentExecutionResponse> executeRequest(
            AgentExecutionRequest request, String requestId) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(
                    AgentExecutionResponse.failure("EMPTY_PRODUCT_QUESTION",
                            "Question must not be blank", false));
        }
        String question = UserQuestionNormalizer.normalize(request.question());
        String original = java.util.Objects.toString(request.input().get("_replyScopeQuestion"), "");
        // Planner descriptions can omit or rewrite constraints. Preserve chronological
        // user context for every catalog/analysis/recommendation stage, even without money.
        if ((isAnalysisOrRecommendationRequest(request)
                || "DISCOVER_PRODUCTS".equalsIgnoreCase(request.operation())
                || "QUERY_HOT_PRODUCTS".equalsIgnoreCase(request.operation()))
                && !original.isBlank()) {
            question = UserQuestionNormalizer.normalize(original);
        }
        ToolUsageCache.start(requestId);
        if ("RESOLVE_READ_ONLY_PRODUCT".equals(request.operation())) {
            var history = request.input().get("conversationHistory") instanceof List<?> values
                    ? values.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                    : List.<String>of();
            var response = factQueryService == null
                    ? AgentExecutionResponse.success("", Map.of("handled", false, "deterministic", true), DomainQualityResult.unknown())
                    : factQueryService.query(question, history, requestId);
            return executionResponse(requestId, response, true);
        }
        if (isAnalysisOrRecommendationRequest(request)) {
            AgentNodeOutput terminal = verifiedDiscoveryReply(request);
            if (terminal != null) {
                Map<String, Object> data = new LinkedHashMap<>(terminal.data());
                data.put(WorkflowOperation.ANALYZE_PRODUCT_DATA.code().equalsIgnoreCase(request.operation())
                        ? "analysis" : "recommendation", terminal.answer());
                return executionResponse(requestId, AgentExecutionResponse.success(terminal.answer(), data,
                        DomainQualityResult.pass(1.0, Boolean.TRUE.equals(data.get("clarificationRequired"))
                                ? "PRODUCT_PREFERENCE_CLARIFICATION" : "PRODUCT_POPULAR_BROWSING")), true);
            }
            // An explicit, successful empty catalog is a business result, not missing
            // context to send through Flash/Pro. Carry it across analysis-only DAG edges.
            if (hasVerifiedEmptyCatalog(request)) {
                String answer = "目前在售商品中还没有找到符合这些条件的款式。"
                        + "您最看重哪项条件？我可以据此帮您继续筛选。";
                String field = WorkflowOperation.ANALYZE_PRODUCT_DATA.code()
                        .equalsIgnoreCase(request.operation()) ? "analysis" : "recommendation";
                return executionResponse(requestId, AgentExecutionResponse.success(answer,
                        Map.of("operation", request.operation(), "products", List.of(),
                                "productCount", 0, field, answer,
                                "sourceNodeIds", List.copyOf(request.predecessorOutputs().keySet())),
                        DomainQualityResult.pass(1.0, "EMPTY_PRODUCT_CATALOG")), true);
            }
            String verifiedContext = buildVerifiedContext(request);
            List<Map<?, ?>> catalog = verifiedProducts(request);
            ToolUsageCache.start(requestId);
            DomainAgentResponse response = WorkflowOperation.ANALYZE_PRODUCT_DATA.code().equalsIgnoreCase(request.operation())
                    ? streamingAgentService.analyzeVerifiedContext(
                            question, verifiedContext, catalog, requestId)
                    : streamingAgentService.verifyAnalysisAndRecommend(
                            question, verifiedContext, catalog, requestId);
            if (WorkflowOperation.RECOMMEND_PRODUCT.code().equalsIgnoreCase(request.operation())) {
                response = ensureEvidenceBackedRecommendation(request, response);
            }
            if (response.quality().isFail()) {
                String code = response.quality().getReasonCodes().isEmpty()
                        ? "PRODUCT_ANALYSIS_FAILED" : response.quality().getReasonCodes().getFirst();
                return executionResponse(requestId, AgentExecutionResponse.failure(
                        code, response.answer(), false), false);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", request.operation());
            if (!catalog.isEmpty()) {
                data.put("budgetAssessment", new StructuredProductRecommendation(
                        question, catalog).budgetData());
            }
            data.put("sourceNodeIds", List.copyOf(request.predecessorOutputs().keySet()));
            // Workflow DSL input bindings address typed node data. Keep answer for
            // display/backward compatibility and expose the same verified content
            // under a stable operation-specific field for downstream nodes.
            if (WorkflowOperation.ANALYZE_PRODUCT_DATA.code().equalsIgnoreCase(request.operation())) {
                data.put("analysis", response.answer());
                // A recommendation node may depend only on this analysis node. Carry the
                // verified catalog forward so transitive evidence is not lost at the DAG edge.
                List<Map<?, ?>> products = verifiedProducts(request);
                if (!products.isEmpty()) {
                    data.put("products", products);
                    data.put("productCount", products.size());
                }
            } else {
                data.put("recommendation", response.answer());
            }
            return executionResponse(requestId, AgentExecutionResponse.success(
                    response.answer(), data, response.quality()),
                    response.quality().getReasonCodes().contains("NO_ELIGIBLE_VERIFIED_PRODUCT"));
        }
        if (productDiscoveryService != null && isDiscoveryRequest(request)) {
            Integer limit = integerInput(request, "candidateLimit", "candidate_limit", "limit");
            String category = textInput(request, "product_category", "category", "product_name");
            long started = System.nanoTime();
            ProductDiscoveryService.DiscoveryResult discovery;
            boolean success = false;
            try {
                // Profile is advisory: it must not silently replace the user's explicit category/budget.
                discovery = productDiscoveryService.discover(question, category, limit);
                success = true;
            } finally {
                // This is an actual catalog capability invocation, not a synthetic LLM tool call.
                ToolUsageCache.record(requestId, "discoverProducts", success,
                        (System.nanoTime() - started) / 1_000_000);
            }
            DomainAgentResponse response = DomainAgentResponse.of(
                    discovery.answer(), discovery.clarificationRequired()
                    ? DomainQualityResult.pass(1.0, "PRODUCT_PREFERENCE_CLARIFICATION")
                    : discovery.productCount() > 0
                    ? discovery.scenarioEvidenceLimited()
                        ? com.example.smartassistant.common.quality.DomainQualityResult.pass(
                                1.0, "PRODUCT_SCENARIO_EVIDENCE_LIMITED")
                        : com.example.smartassistant.common.quality.DomainQualityResult.pass(
                                1.0, "PRODUCT_DISCOVERY_DATA")
                    : com.example.smartassistant.common.quality.DomainQualityResult.pass(
                            1.0, "EMPTY_PRODUCT_CATALOG"));
            Map<String, Object> data = new LinkedHashMap<>(Map.of(
                    "products", discovery.products(),
                    "productCount", discovery.productCount(),
                    "popularityBased", discovery.popularityBased(),
                    "scenarioEvidenceLimited", discovery.scenarioEvidenceLimited(),
                    "category", discovery.category(),
                    "clarificationRequired", discovery.clarificationRequired(),
                    "browsingOnly", discovery.browsingOnly()));
            if (discovery.clarificationRequired() && !discovery.missingFields().isEmpty()) {
                data.put("clarificationRequest", new com.example.smartassistant.common.agent.protocol.ClarificationRequest(
                        "product", "DISCOVER_PRODUCTS", discovery.missingFields()).toMap());
            }
            return executionResponse(requestId, AgentExecutionResponse.success(
                    response.answer(), data, response.quality()), true);
        }
        ToolUsageCache.start(requestId);
        // Fixed product facts must not inherit historical shopping constraints from the
        // profile. Analysis/recommendation receive advisory profile in a separate context above.
        DomainAgentResponse response = streamingAgentService.executeWithQuality(
                question, requestId);
        if (response.quality().isFail()) {
            if (factQueryService != null && response.quality().getReasonCodes().stream().anyMatch(code -> code.startsWith("MODEL_"))) {
                String fallbackQuestion = java.util.Objects.toString(request.input().get("_replyScopeQuestion"), question);
                var fallback = factQueryService.query(fallbackQuestion, List.of(), requestId);
                if (Boolean.TRUE.equals(fallback.data().get("handled")))
                    return executionResponse(requestId, fallback, false);
            }
            String code = response.quality().getReasonCodes().isEmpty()
                    ? "PRODUCT_EXECUTION_ERROR" : response.quality().getReasonCodes().getFirst();
            return executionResponse(requestId, AgentExecutionResponse.failure(code, response.answer(),
                    com.example.smartassistant.spi.ProductCatalogUnavailableException.CODE.equals(code)), false);
        }
        return executionResponse(requestId,
                AgentExecutionResponse.success(response.answer(), response.quality()), false);
    }

    /** Every protocol exit, including deterministic and audit-failure exits, carries telemetry. */
    private ResponseEntity<AgentExecutionResponse> executionResponse(
            String requestId, AgentExecutionResponse response, boolean noModelInvocation) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (response.quality() != null) {
            builder.header(DomainQualityHeaders.STATUS, response.quality().status())
                    .header(DomainQualityHeaders.SCORE, String.valueOf(response.quality().score()))
                    .header(DomainQualityHeaders.REASON_CODES,
                            String.join(",", response.quality().reasonCodes()));
        }
        TokenUsageCache.TokenUsage usage = TokenUsageCache.consume(requestId);
        if (usage == null && noModelInvocation) {
            usage = new TokenUsageCache.TokenUsage(0L, 0L, 0L);
        }
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

    private boolean isDiscoveryRequest(AgentExecutionRequest request) {
        return WorkflowOperation.QUERY_HOT_PRODUCTS.code().equalsIgnoreCase(request.operation())
                || WorkflowOperation.DISCOVER_PRODUCTS.code().equalsIgnoreCase(request.operation())
                || productDiscoveryService.supports(request.question());
    }

    private static Integer integerInput(AgentExecutionRequest request, String... keys) {
        for (String key : keys) {
            Object value = request.input().get(key);
            if (value instanceof Number number) return number.intValue();
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                    // Try the next compatible key.
                }
            }
        }
        return null;
    }

    private static String textInput(AgentExecutionRequest request, String... keys) {
        for (String key : keys) {
            Object value = request.input().get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }


    /**
     * 创建 SSE 事件
     */
    private org.springframework.http.codec.ServerSentEvent<String> createSSEEvent(
            String type, int step, String content) {
        return createSSEEvent(type, step, content, null, null);
    }

    private org.springframework.http.codec.ServerSentEvent<String> createSSEEvent(
            String type, int step, String content, String toolName, String arguments) {

        StringBuilder json = new StringBuilder("{");
        json.append("\"type\":\"").append(type).append("\"");

        if (step > 0) {
            json.append(",\"step\":").append(step);
        }
        if (content != null) {
            json.append(",\"content\":\"").append(escapeJson(content)).append("\"");
        }
        if (toolName != null) {
            json.append(",\"toolName\":\"").append(toolName).append("\"");
        }
        if (arguments != null) {
            json.append(",\"arguments\":\"").append(escapeJson(arguments)).append("\"");
        }

        json.append("}");

        return org.springframework.http.codec.ServerSentEvent.<String>builder()
                .id(String.valueOf(step))
                .event(type)
                .data(json.toString())
                .build();
    }

    /**
     * 转义 JSON 特殊字符
     */
    private org.springframework.http.codec.ServerSentEvent<String> createTokenUsageEvent(String requestId) {
        TokenUsageCache.TokenUsage usage = TokenUsageCache.consume(requestId);
        if (usage == null || usage.totalTokens() == null) return null;

        StringBuilder json = new StringBuilder("{\"type\":\"token_usage\"");
        if (usage.promptTokens() != null) {
            json.append(",\"promptTokens\":").append(usage.promptTokens());
        }
        if (usage.completionTokens() != null) {
            json.append(",\"completionTokens\":").append(usage.completionTokens());
        }
        json.append(",\"totalTokens\":").append(usage.totalTokens()).append('}');
        return org.springframework.http.codec.ServerSentEvent.<String>builder()
                .event("token_usage")
                .data(json.toString())
                .build();
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
