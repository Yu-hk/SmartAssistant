package com.example.smartassistant.router.controller;

import com.example.smartassistant.common.agent.ExecutionTraceStore;
import com.example.smartassistant.common.agent.AgentExecutionState;
import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.response.ApiResponse;
import com.example.smartassistant.common.tool.ToolLogContext;
import com.example.smartassistant.common.tracing.DistributedTracingService;
import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RouteResponse;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.service.core.RouterService;
import com.example.smartassistant.router.service.tool.RoutingToolChecker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Router Controller - 路由 API
 * <p>
 * 所有接口统一返回 {@link ApiResponse} 格式。
 */
@RestController
@RequestMapping("/api/router")
public class RouterController {

    private static final Logger log = LoggerFactory.getLogger(RouterController.class);

    private final RouterService routerService;
    private final DistributedTracingService tracingService;
    private final RoutingToolChecker routingToolChecker;
    private final ExecutionTraceStore executionTraceStore;

    public RouterController(RouterService routerService,
                           DistributedTracingService tracingService,
                           RoutingToolChecker routingToolChecker,
                           @Autowired(required = false) ExecutionTraceStore executionTraceStore) {
        this.routerService = routerService;
        this.tracingService = tracingService;
        this.routingToolChecker = routingToolChecker;
        this.executionTraceStore = executionTraceStore;
    }

    /**
     * 智能路由接口
     */
    @PostMapping("/route")
    public ApiResponse<RouteResponse> route(
            @RequestHeader("X-User-Id") Long authenticatedUserId,
            @Valid @RequestBody RouteRequest request) {
        bindAuthenticatedUser(request, authenticatedUserId);
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = extractRequestId(request.getQuestion());
        }
        String threadId = extractThreadId(request.getQuestion());

        tracingService.startTrace(requestId, threadId);
        ToolUsageCache.start(requestId);
        ToolLogContext.setRequestId(requestId);
        try {
            tracingService.injectToLog("收到路由请求: userId=" + request.getUserId());

            log.info("[Router API] 收到路由请求: userId={}, question={}, requestId={}",
                    request.getUserId(), truncate(request.getQuestion()), requestId);

            long startTime = System.currentTimeMillis();

            RoutingResult routingResult = routerService.route(request);
            routerService.recordConversation(request, routingResult);
            TokenUsageCache.TokenUsage tokenUsage = TokenUsageCache.consume(requestId);
            ToolUsageCache.ToolUsage toolUsage = ToolUsageCache.consume(requestId);
            long latency = System.currentTimeMillis() - startTime;

            RouteResponse response = RouteResponse.builder()
                    .agentName(routingResult.getAgentName() != null ? routingResult.getAgentName() : "determined_by_router")
                    .result(routingResult.getResult())
                    .confidence(routingResult.getConfidence() != null ? routingResult.getConfidence() : 0.9)
                    .routingMethod("LLM_ROUTING")
                    .intentTag(routingResult.getIntentTag())
                    .fromCache(routingResult.getFromCache() != null && routingResult.getFromCache())
                    .clarification(Boolean.TRUE.equals(routingResult.getClarification()))
                    .promptTokens(tokenUsage != null ? tokenUsage.promptTokens() : null)
                    .completionTokens(tokenUsage != null ? tokenUsage.completionTokens() : null)
                    .totalTokens(tokenUsage != null ? tokenUsage.totalTokens() : null)
                    .toolUsageComplete(toolUsage != null ? toolUsage.complete() : null)
                    .toolCalls(toolUsage != null ? toolUsage.calls() : null)
                    .build();

            log.info("[Router API] 路由完成: latency={}ms, resultLength={}, agent={}",
                    latency, routingResult.getResult() != null ? routingResult.getResult().length() : 0,
                    routingResult.getAgentName());

            return ApiResponse.success(response);
        } finally {
            ToolLogContext.clear();
            tracingService.endTrace();
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("Router Service is running");
    }

    /**
     * ReactAgent 智能路由测试接口
     */
    @PostMapping("/react/route")
    public ApiResponse<Map<String, Object>> reactRoute(
            @RequestHeader("X-User-Id") Long authenticatedUserId,
            @Valid @RequestBody RouteRequest request) {
        bindAuthenticatedUser(request, authenticatedUserId);
        log.info("[ReactAgent Router API] 收到 ReactAgent 路由请求: userId={}, question={}",
                request.getUserId(), truncate(request.getQuestion()));

        long startTime = System.currentTimeMillis();

        RoutingResult routingResult = routerService.route(request);
        long latency = System.currentTimeMillis() - startTime;

        if (routingResult == null || routingResult.getAgentName() == null) {
            return ApiResponse.error(500, "路由决策失败");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("serviceName", routingResult.getAgentName());
        response.put("confidence", routingResult.getConfidence());
        response.put("latency_ms", latency);
        response.put("note", "这是路由决策结果（含 Agent 回复）");

        log.info("[ReactAgent Router API] 路由完成: serviceName={}, latency={}ms",
                routingResult.getAgentName(), latency);

        return ApiResponse.success(response);
    }

    /**
     * 对比测试：传统路由 vs ReactAgent 路由
     */
    @PostMapping("/compare/route")
    public ApiResponse<Map<String, Object>> compareRoute(
            @RequestHeader("X-User-Id") Long authenticatedUserId,
            @Valid @RequestBody RouteRequest request) {
        bindAuthenticatedUser(request, authenticatedUserId);
        log.info("[Compare Router API] 开始对比测试: userId={}, question={}",
                request.getUserId(), truncate(request.getQuestion()));

        Map<String, Object> comparison = new HashMap<>();

        // 1. 传统路由
        long traditionalStart = System.currentTimeMillis();
        try {
            RoutingResult traditionalResult = routerService.route(request);
            long traditionalLatency = System.currentTimeMillis() - traditionalStart;

            comparison.put("traditional_routing", Map.of(
                    "success", true,
                    "result_length", traditionalResult.getResult() != null ? traditionalResult.getResult().length() : 0,
                    "latency_ms", traditionalLatency,
                    "method", "STRATEGY_MANAGER"
            ));
        } catch (Exception e) {
            comparison.put("traditional_routing", Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }

        // 2. ReactAgent 路由决策
        long reactStart = System.currentTimeMillis();
        try {
            RoutingResult routingResult = routerService.route(request);
            long reactLatency = System.currentTimeMillis() - reactStart;

            if (routingResult != null) {
                comparison.put("keyword_routing", Map.of(
                        "success", true,
                        "agent", routingResult.getAgentName(),
                        "result_length", routingResult.getResult() != null ? routingResult.getResult().length() : 0,
                        "latency_ms", reactLatency,
                        "method", "KEYWORD_ROUTING"
                ));
            } else {
                comparison.put("keyword_routing", Map.of(
                        "success", false,
                        "error", "路由决策为空"
                ));
            }
        } catch (Exception e) {
            comparison.put("keyword_routing", Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }

        comparison.put("question", request.getQuestion());
        comparison.put("userId", request.getUserId());

        return ApiResponse.success(comparison);
    }

    /**
     * 测试接口 - 无需 JWT Token
     */
    @PostMapping("/test/route")
    public ApiResponse<RouteResponse> testRoute(
            @RequestHeader("X-User-Id") Long authenticatedUserId,
            @Valid @RequestBody RouteRequest request) {
        bindAuthenticatedUser(request, authenticatedUserId);
        log.info("[Router Test API] 收到测试路由请求: userId={}, question={}",
                request.getUserId(), truncate(request.getQuestion()));

        long startTime = System.currentTimeMillis();

        RoutingResult routingResult = routerService.route(request);
        long latency = System.currentTimeMillis() - startTime;

        RouteResponse response = RouteResponse.builder()
                .agentName(routingResult.getAgentName() != null ? routingResult.getAgentName() : "determined_by_router")
                .result(routingResult.getResult())
                .confidence(routingResult.getConfidence() != null ? routingResult.getConfidence() : 0.9)
                .routingMethod("LLM_ROUTING")
                .clarification(Boolean.TRUE.equals(routingResult.getClarification()))
                .build();

        log.info("[Router Test API] 测试路由完成: latency={}ms, resultLength={}",
                latency, routingResult.getResult() != null ? routingResult.getResult().length() : 0);

        return ApiResponse.success(response);
    }

    /**
     * 工具健康检查端点。
     */
    @GetMapping("/tools/health")
    public ApiResponse<Map<String, Object>> toolHealth() {
        return ApiResponse.success(routingToolChecker.getAllAgentsHealth());
    }

    /**
     * Agent 执行事件查看端点。
     */
    @GetMapping("/events/{requestId}")
    public ApiResponse<List<AgentExecutionState.StateTransition>> getEvents(
            @PathVariable("requestId") String requestId) {
        List<AgentExecutionState.StateTransition> events = executionTraceStore != null
                ? executionTraceStore.getEvents(requestId)
                : List.of();
        return ApiResponse.success(events);
    }

    // ========== 工具方法 ==========

    /** Binds the gateway-authenticated identity and rejects body spoofing. */
    private void bindAuthenticatedUser(RouteRequest request, Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user identity");
        }
        if (request.getUserId() != null && !authenticatedUserId.equals(request.getUserId())) {
            log.warn("[Router API] rejected mismatched identity: headerUserId={}, bodyUserId={}",
                    authenticatedUserId, request.getUserId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Request user does not match authenticated user");
        }
        request.setUserId(authenticatedUserId);
    }

    private String truncate(String str) {
        if (str == null) return "";
        return str.length() > 50 ? str.substring(0, 50) + "..." : str;
    }

    private String extractRequestId(String question) {
        if (question == null || !question.trim().startsWith("{")) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(question);
            if (rootNode.has("metadata") && rootNode.get("metadata").has("requestId")) {
                return rootNode.get("metadata").get("requestId").asText();
            }
        } catch (Exception e) {
            log.debug("[Router] 无法从 Prompt 中提取 requestId: {}", e.getMessage());
        }
        return null;
    }

    private String extractThreadId(String question) {
        if (question == null || !question.trim().startsWith("{")) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(question);
            if (rootNode.has("metadata") && rootNode.get("metadata").has("sessionId")) {
                return rootNode.get("metadata").get("sessionId").asText();
            }
        } catch (Exception e) {
            log.debug("[Router] 无法从 Prompt 中提取 threadId: {}", e.getMessage());
        }
        return null;
    }
}
