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
import com.example.smartassistant.routing.contract.RoutingKeys;
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
            Map<String, Object> data = Map.of(
                    "products", discovery.products(),
                    "productCount", discovery.productCount(),
                    "popularityBased", discovery.popularityBased(),
                    "scenarioEvidenceLimited", discovery.scenarioEvidenceLimited(),
                    "category", discovery.category(),
                    "clarificationRequired", discovery.clarificationRequired(),
                    "browsingOnly", discovery.browsingOnly());
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

    private static boolean isAnalysisOrRecommendationRequest(AgentExecutionRequest request) {
        return WorkflowOperation.ANALYZE_PRODUCT_DATA.code().equalsIgnoreCase(request.operation())
                || WorkflowOperation.RECOMMEND_PRODUCT.code().equalsIgnoreCase(request.operation());
    }

    private static String buildVerifiedContext(AgentExecutionRequest request) {
        StringBuilder context = new StringBuilder();
        String userProfile = textInput(request, RoutingKeys.USER_PROFILE_INPUT);
        if (!userProfile.isBlank()) {
            context.append("[用户画像]\n").append(userProfile.trim()).append("\n\n");
        }
        request.predecessorOutputs().forEach((nodeId, output) -> {
            context.append("[上游节点 ").append(nodeId).append("]\n");
            if (output.data() != null && !output.data().isEmpty()) {
                context.append("结构化数据：").append(output.data()).append('\n');
            }
            // Discovery's prose duplicates its typed product list. Keep only the structured
            // evidence there, while retaining analysis prose whose data envelope is metadata-only.
            boolean structuredCatalog = output.data() != null
                    && output.data().containsKey("products");
            if (structuredCatalog) {
                // These are backend field definitions, not facts supplied by the user or model.
                context.append("目录字段口径：popularity 仅为目录 sales_30d 记录的站内近30天销量，不累加历史订单，不代表全网热度；")
                        .append("rating 为5分制评分，reviewCount 为评价数量。\n");
            }
            if (!structuredCatalog && output.answer() != null && !output.answer().isBlank()) {
                context.append(output.answer().trim()).append('\n');
            }
            context.append('\n');
        });
        return context.toString().trim();
    }

    /**
     * A conservative model may correctly identify tied or incomplete evidence but then refuse
     * to show any candidate at all. For a recommendation/list request that is unnecessarily
     * unhelpful: the typed discovery result already contains safe, verified facts. Preserve a
     * model recommendation that references a real candidate; otherwise render the verified
     * candidates deterministically and disclose why no unique winner can be selected.
     */
    private static DomainAgentResponse ensureEvidenceBackedRecommendation(
            AgentExecutionRequest request, DomainAgentResponse modelResponse) {
        // A factual audit rejection must never become a recommendation merely because
        // there are catalog entries. They may violate the user's hard constraints.
        if (modelResponse.quality().isFail()
                || modelResponse.quality().getReasonCodes().contains("NO_ELIGIBLE_VERIFIED_PRODUCT")) return modelResponse;
        List<Map<?, ?>> products = verifiedProducts(request);
        if (products.isEmpty() || mentionsVerifiedProduct(modelResponse.answer(), products)) {
            return modelResponse;
        }

        List<Map<?, ?>> displayed = products.stream().limit(5).toList();
        StringBuilder answer = new StringBuilder("当前可核实的商品候选：\n");
        int index = 1;
        Object sharedPopularity = null;
        boolean samePopularity = displayed.size() > 1;
        for (Map<?, ?> product : displayed) {
            String code = text(product.get("code"));
            String name = text(product.get("name"));
            String price = decimalText(product.get("price"));
            String stock = text(product.get("stock"));
            Object popularity = product.get("popularity");
            if (!(popularity instanceof Number count) || count.doubleValue() <= 0) {
                samePopularity = false;
            }
            if (sharedPopularity == null) sharedPopularity = popularity;
            else if (!String.valueOf(sharedPopularity).equals(String.valueOf(popularity))) {
                samePopularity = false;
            }
            answer.append(index++).append(". ").append(name);
            if (!code.isBlank()) answer.append("（").append(code).append("）");
            if (!price.isBlank()) answer.append(" — ¥").append(price);
            if (!stock.isBlank()) answer.append("，库存：").append(stock);
            if (popularity instanceof Number count && count.doubleValue() > 0) {
                answer.append("，近30天站内销量：").append(popularity);
            }
            if (!text(product.get("spec")).isBlank()) {
                answer.append("，规格：").append(text(product.get("spec")));
            }
            if (product.get("rating") instanceof Number rating && rating.doubleValue() > 0) {
                answer.append("，评分：").append(decimalText(rating)).append("/5");
            }
            if (product.get("reviewCount") instanceof Number count && count.longValue() > 0) {
                answer.append("，评价数：").append(count);
            }
            answer.append('\n');
        }
        if (samePopularity && sharedPopularity != null) {
            answer.append("\n以上展示候选的近30天站内销量均为 ").append(sharedPopularity)
                    .append("，仅凭该销量无法区分优先顺序。");
        } else {
            answer.append("\n以上候选来自当前商品目录。");
        }
        answer.append("具体用途的适配性仍需结合相应规格或实测核实，不能仅凭销量认定最适合。");
        return DomainAgentResponse.of(answer.toString().trim(),
                DomainQualityResult.warn(0.8,
                        "PRODUCT_RECOMMENDATION_VERIFIED_CANDIDATE_FALLBACK",
                        "PRODUCT_RECOMMENDATION_EVIDENCE_LIMITED"));
    }

    /** Preserve clarification/browse results across both direct and transitive DAG edges. */
    private static AgentNodeOutput verifiedDiscoveryReply(AgentExecutionRequest request) {
        AgentNodeOutput reply = null;
        for (AgentNodeOutput output : request.predecessorOutputs().values()) {
            if (!"SUCCEEDED".equals(output.status())) return null;
            // Do not let one terminal marker hide conflicting catalog evidence on another edge.
            if (output.data().containsKey("products")
                    && !Boolean.TRUE.equals(output.data().get("clarificationRequired"))
                    && !Boolean.TRUE.equals(output.data().get("browsingOnly"))) return null;
            if (Boolean.TRUE.equals(output.data().get("clarificationRequired"))
                    || Boolean.TRUE.equals(output.data().get("browsingOnly"))) {
                if (output.answer() == null || output.answer().isBlank()) return null;
                if (reply != null && !reply.answer().equals(output.answer())) return null;
                reply = output;
            }
        }
        return reply;
    }

    private static boolean hasVerifiedEmptyCatalog(AgentExecutionRequest request) {
        boolean emptyCatalog = false;
        for (AgentNodeOutput output : request.predecessorOutputs().values()) {
            if (!"SUCCEEDED".equals(output.status())) return false;
            Object products = output.data().get("products");
            // Conflicting/nonempty evidence must still be audited, not hidden as no match.
            if (products instanceof List<?> items && !items.isEmpty()) return false;
            Object count = output.data().get("productCount");
            if (products instanceof List<?> items && items.isEmpty()
                    && count instanceof Number number && number.doubleValue() == 0) {
                emptyCatalog = true;
            }
        }
        return emptyCatalog;
    }

    private static List<Map<?, ?>> verifiedProducts(AgentExecutionRequest request) {
        Map<String, Map<?, ?>> catalog = new LinkedHashMap<>();
        for (AgentNodeOutput output : request.predecessorOutputs().values()) {
            if (!"SUCCEEDED".equals(output.status())) return List.of();
            Object value = output.data().get("products");
            if (!(value instanceof List<?> items) || items.isEmpty()) continue;
            for (Object item : items) {
                Map<?, ?> product;
                if (item instanceof Map<?, ?> map) product = map;
                else if (item instanceof com.example.smartassistant.spi.ProductBackend.ProductSummary summary) {
                    product = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(summary, Map.class);
                } else return List.of();
                String code = text(product.get("code"));
                if (code.isBlank() || text(product.get("name")).isBlank()) return List.of();
                Map<?, ?> previous = catalog.putIfAbsent(code, product);
                if (previous != null && !previous.equals(product)) return List.of();
            }
        }
        return List.copyOf(catalog.values());
    }

    private static boolean mentionsVerifiedProduct(String answer, List<Map<?, ?>> products) {
        if (answer == null || answer.isBlank()) return false;
        String normalizedAnswer = normalizeProductReference(answer);
        for (Map<?, ?> product : products) {
            String code = normalizeProductReference(text(product.get("code")));
            String name = normalizeProductReference(text(product.get("name")));
            if ((!code.isBlank() && normalizedAnswer.contains(code))
                    || (!name.isBlank() && normalizedAnswer.contains(name))) return true;
        }
        return false;
    }

    private static String normalizeProductReference(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String decimalText(Object value) {
        if (value == null) return "";
        try {
            return new java.math.BigDecimal(String.valueOf(value))
                    .stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return text(value);
        }
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
