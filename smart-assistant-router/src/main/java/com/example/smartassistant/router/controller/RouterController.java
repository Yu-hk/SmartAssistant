package com.example.smartassistant.router.controller;

import com.example.smartassistant.common.response.ApiResponse;
import com.example.smartassistant.common.tracing.DistributedTracingService;
import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RouteResponse;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.service.core.RouterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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

    public RouterController(RouterService routerService,
                           DistributedTracingService tracingService) {
        this.routerService = routerService;
        this.tracingService = tracingService;
    }

    /**
     * 智能路由接口
     */
    @PostMapping("/route")
    public ApiResponse<RouteResponse> route(@Valid @RequestBody RouteRequest request) {
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = extractRequestId(request.getQuestion());
        }
        String threadId = extractThreadId(request.getQuestion());

        tracingService.startTrace(requestId, threadId);
        tracingService.injectToLog("收到路由请求: userId=" + request.getUserId());

        log.info("[Router API] 收到路由请求: userId={}, question={}, requestId={}",
                request.getUserId(), truncate(request.getQuestion()), requestId);

        long startTime = System.currentTimeMillis();

        RoutingResult routingResult = routerService.route(request);
        long latency = System.currentTimeMillis() - startTime;

        RouteResponse response = RouteResponse.builder()
                .agentName(routingResult.getAgentName() != null ? routingResult.getAgentName() : "determined_by_router")
                .result(routingResult.getResult())
                .confidence(routingResult.getConfidence() != null ? routingResult.getConfidence() : 0.9)
                .routingMethod("LLM_ROUTING")
                .intentTag(routingResult.getIntentTag())
                .fromCache(routingResult.getFromCache() != null && routingResult.getFromCache())
                .build();

        log.info("[Router API] 路由完成: latency={}ms, resultLength={}, agent={}",
                latency, routingResult.getResult() != null ? routingResult.getResult().length() : 0,
                routingResult.getAgentName());

        tracingService.endTrace();

        return ApiResponse.success(response);
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
    public ApiResponse<Map<String, Object>> reactRoute(@Valid @RequestBody RouteRequest request) {
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
    public ApiResponse<Map<String, Object>> compareRoute(@Valid @RequestBody RouteRequest request) {
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
    public ApiResponse<RouteResponse> testRoute(@Valid @RequestBody RouteRequest request) {
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
                .build();

        log.info("[Router Test API] 测试路由完成: latency={}ms, resultLength={}",
                latency, routingResult.getResult() != null ? routingResult.getResult().length() : 0);

        return ApiResponse.success(response);
    }

    // ========== 工具方法 ==========

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
