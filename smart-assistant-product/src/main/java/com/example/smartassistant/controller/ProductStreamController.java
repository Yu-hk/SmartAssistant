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
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(
                    AgentExecutionResponse.failure("EMPTY_PRODUCT_QUESTION",
                            "Question must not be blank", false));
        }
        String question = UserQuestionNormalizer.normalize(request.question());
        if (isAnalysisOrRecommendationRequest(request)) {
            String verifiedContext = buildVerifiedContext(request);
            ToolUsageCache.start(requestId);
            DomainAgentResponse response = WorkflowOperation.ANALYZE_PRODUCT_DATA.code().equalsIgnoreCase(request.operation())
                    ? streamingAgentService.analyzeVerifiedContext(
                            question, verifiedContext, requestId)
                    : streamingAgentService.verifyAnalysisAndRecommend(
                            question, verifiedContext, requestId);
            if (WorkflowOperation.RECOMMEND_PRODUCT.code().equalsIgnoreCase(request.operation())) {
                response = ensureEvidenceBackedRecommendation(request, response);
            }
            if (response.quality().isFail()) {
                String code = response.quality().getReasonCodes().isEmpty()
                        ? "PRODUCT_ANALYSIS_FAILED" : response.quality().getReasonCodes().getFirst();
                return ResponseEntity.ok(AgentExecutionResponse.failure(
                        code, response.answer(), false));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", request.operation());
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
            return ResponseEntity.ok(AgentExecutionResponse.success(
                    response.answer(), data, response.quality()));
        }
        if (productDiscoveryService != null && isDiscoveryRequest(request)) {
            Integer limit = integerInput(request, "candidateLimit", "candidate_limit", "limit");
            String category = textInput(request, "product_category", "category", "product_name");
            ProductDiscoveryService.DiscoveryResult discovery =
                    productDiscoveryService.discover(withUserProfile(question, request), category, limit);
            DomainAgentResponse response = DomainAgentResponse.of(
                    discovery.answer(), discovery.productCount() > 0
                    ? discovery.scenarioEvidenceLimited()
                        ? com.example.smartassistant.common.quality.DomainQualityResult.pass(
                                1.0, "PRODUCT_SCENARIO_EVIDENCE_LIMITED")
                        : com.example.smartassistant.common.quality.DomainQualityResult.pass(
                                1.0, "PRODUCT_DISCOVERY_DATA")
                    : com.example.smartassistant.common.quality.DomainQualityResult.warn(
                            0.5, "EMPTY_PRODUCT_CATALOG"));
            Map<String, Object> data = Map.of(
                    "products", discovery.products(),
                    "productCount", discovery.productCount(),
                    "popularityBased", discovery.popularityBased(),
                    "scenarioEvidenceLimited", discovery.scenarioEvidenceLimited(),
                    "category", discovery.category());
            return ResponseEntity.ok(AgentExecutionResponse.success(
                    response.answer(), data, response.quality()));
        }
        ToolUsageCache.start(requestId);
        DomainAgentResponse response = streamingAgentService.executeWithQuality(
                withUserProfile(question, request), requestId);
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
            if (!structuredCatalog && output.answer() != null && !output.answer().isBlank()) {
                context.append(output.answer().trim()).append('\n');
            }
            context.append('\n');
        });
        return context.toString().trim();
    }

    private static String withUserProfile(String question, AgentExecutionRequest request) {
        String userProfile = textInput(request, RoutingKeys.USER_PROFILE_INPUT);
        if (userProfile.isBlank()) return question;
        return question + "\n\n[用户画像]\n" + userProfile;
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
        List<Map<?, ?>> products = verifiedProducts(request);
        if (products.isEmpty() || mentionsVerifiedProduct(modelResponse.answer(), products)) {
            return modelResponse;
        }

        StringBuilder answer = new StringBuilder("当前热门商品候选（近期订单热度并列）：\n");
        int index = 1;
        Object sharedPopularity = null;
        boolean samePopularity = true;
        for (Map<?, ?> product : products.stream().limit(5).toList()) {
            String code = text(product.get("code"));
            String name = text(product.get("name"));
            String price = decimalText(product.get("price"));
            String stock = text(product.get("stock"));
            Object popularity = product.get("popularity");
            if (sharedPopularity == null) sharedPopularity = popularity;
            else if (!String.valueOf(sharedPopularity).equals(String.valueOf(popularity))) {
                samePopularity = false;
            }
            answer.append(index++).append(". ").append(name);
            if (!code.isBlank()) answer.append("（").append(code).append("）");
            if (!price.isBlank()) answer.append(" — ¥").append(price);
            if (!stock.isBlank()) answer.append("，库存：").append(stock);
            if (popularity != null) answer.append("，近期订单：").append(popularity);
            answer.append('\n');
        }
        if (samePopularity && sharedPopularity != null) {
            answer.append("\n这些候选的近期订单数均为 ").append(sharedPopularity)
                    .append("，现有数据无法区分唯一第一；");
        } else {
            answer.append("\n以上候选来自当前真实商品与订单数据；");
        }
        answer.append("口碑数据和您的预算、用途偏好尚未提供。告诉我预算或使用场景后，")
                .append("我可以继续缩小范围。 ");
        return DomainAgentResponse.of(answer.toString().trim(),
                DomainQualityResult.warn(0.8,
                        "PRODUCT_RECOMMENDATION_VERIFIED_CANDIDATE_FALLBACK",
                        "PRODUCT_RECOMMENDATION_EVIDENCE_LIMITED"));
    }

    private static List<Map<?, ?>> verifiedProducts(AgentExecutionRequest request) {
        for (AgentNodeOutput output : request.predecessorOutputs().values()) {
            Object value = output.data().get("products");
            if (!(value instanceof List<?> items) || items.isEmpty()) continue;
            List<Map<?, ?>> products = new java.util.ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> product) products.add(product);
            }
            if (!products.isEmpty()) return products;
        }
        return List.of();
    }

    private static boolean mentionsVerifiedProduct(String answer, List<Map<?, ?>> products) {
        if (answer == null || answer.isBlank()) return false;
        for (Map<?, ?> product : products) {
            String code = text(product.get("code"));
            String name = text(product.get("name"));
            if ((!code.isBlank() && answer.contains(code))
                    || (!name.isBlank() && answer.contains(name))) return true;
        }
        return false;
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
