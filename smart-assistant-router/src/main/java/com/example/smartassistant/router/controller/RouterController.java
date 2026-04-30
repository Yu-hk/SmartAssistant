package com.example.smartassistant.router.controller;

import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RouteResponse;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.service.DistributedTracingService;
import com.example.smartassistant.router.service.RouterService;
import com.example.smartassistant.router.strategy.RoutingStrategyManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Router Controller - 路由 API
 */
@RestController
@RequestMapping("/api/router")
public class RouterController {

    private static final Logger log = LoggerFactory.getLogger(RouterController.class);

    private final RouterService routerService;
    private final RoutingStrategyManager strategyManager;
    private final DistributedTracingService tracingService;

    public RouterController(RouterService routerService,
                           RoutingStrategyManager strategyManager,
                           DistributedTracingService tracingService) {
        this.routerService = routerService;
        this.strategyManager = strategyManager;
        this.tracingService = tracingService;
    }

    /**
     * 智能路由接口
     */
    @PostMapping("/route")
    public ResponseEntity<RouteResponse> route(@Valid @RequestBody RouteRequest request) {
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

        try {
            RoutingResult routingResult = routerService.route(request);
            long latency = System.currentTimeMillis() - startTime;

            RouteResponse response = RouteResponse.builder()
                    .agentName(routingResult.getAgentName() != null ? routingResult.getAgentName() : "determined_by_router")
                    .result(routingResult.getResult())
                    .confidence(routingResult.getConfidence() != null ? routingResult.getConfidence() : 0.9)
                    .routingMethod("LLM_ROUTING")
                    .intentTag(routingResult.getIntentTag())  // ⭐ 传递意图标签
                    .build();

            log.info("[Router API] 路由完成: latency={}ms, resultLength={}, agent={}",
                    latency, routingResult.getResult() != null ? routingResult.getResult().length() : 0,
                    routingResult.getAgentName());

            tracingService.endTrace();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Router API] 路由失败: {}", e.getMessage(), e);
            tracingService.endTrace();

            RouteResponse errorResponse = RouteResponse.builder()
                    .error(e.getMessage())
                    .build();

            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Router Service is running");
    }

    /**
     * ReactAgent 智能路由测试接口
     */
    @PostMapping("/react/route")
    public ResponseEntity<Map<String, Object>> reactRoute(@Valid @RequestBody RouteRequest request) {
        log.info("[ReactAgent Router API] 收到 ReactAgent 路由请求: userId={}, question={}",
                request.getUserId(), truncate(request.getQuestion()));

        long startTime = System.currentTimeMillis();

        try {
            var context = Map.<String, Object>of("userId", request.getUserId());
            var decision = strategyManager.executeRouting(request.getQuestion(), context);

            long latency = System.currentTimeMillis() - startTime;

            if (decision == null) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("error", "路由决策失败");
                errorResponse.put("latency_ms", latency);
                return ResponseEntity.internalServerError().body(errorResponse);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("serviceName", decision.getAgentName());
            response.put("confidence", decision.getConfidence());
            response.put("reason", decision.getReason());
            response.put("extractedInfo", decision.getExtractedContext());
            response.put("routingMethod", decision.getRoutingMethod());
            response.put("latency_ms", latency);
            response.put("note", "这是路由决策结果，未调用远程 Agent");

            log.info("[ReactAgent Router API] ReactAgent 路由完成: serviceName={}, confidence={}, latency={}ms",
                    decision.getAgentName(), decision.getConfidence(), latency);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[ReactAgent Router API] ReactAgent 路由失败: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());

            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 对比测试：传统路由 vs ReactAgent 路由
     */
    @PostMapping("/compare/route")
    public ResponseEntity<Map<String, Object>> compareRoute(@Valid @RequestBody RouteRequest request) {
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
            var context = Map.<String, Object>of("userId", request.getUserId());
            var decision = strategyManager.executeRouting(request.getQuestion(), context);
            long reactLatency = System.currentTimeMillis() - reactStart;

            if (decision != null) {
                comparison.put("react_agent_routing", Map.of(
                        "success", true,
                        "serviceName", decision.getAgentName(),
                        "confidence", decision.getConfidence(),
                        "reason", decision.getReason(),
                        "latency_ms", reactLatency,
                        "method", "REACT_AGENT_ROUTING"
                ));
            } else {
                comparison.put("react_agent_routing", Map.of(
                        "success", false,
                        "error", "路由决策失败",
                        "latency_ms", reactLatency
                ));
            }
        } catch (Exception e) {
            comparison.put("react_agent_routing", Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }

        comparison.put("question", request.getQuestion());
        comparison.put("userId", request.getUserId());

        return ResponseEntity.ok(comparison);
    }

    /**
     * 测试接口 - 无需 JWT Token
     */
    @PostMapping("/test/route")
    public ResponseEntity<RouteResponse> testRoute(@Valid @RequestBody RouteRequest request) {
        log.info("[Router Test API] 收到测试路由请求: userId={}, question={}",
                request.getUserId(), truncate(request.getQuestion()));

        long startTime = System.currentTimeMillis();

        try {
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

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Router Test API] 测试路由失败: {}", e.getMessage(), e);

            RouteResponse errorResponse = RouteResponse.builder()
                    .error(e.getMessage())
                    .build();

            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    private String truncate(String str) {
        if (str == null) return "";
        return str.length() > 50 ? str.substring(0, 50) + "..." : str;
    }

    /**
     * 从 JSON Prompt 中提取 requestId
     */
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

    /**
     * 从 JSON Prompt 中提取 threadId
     */
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
