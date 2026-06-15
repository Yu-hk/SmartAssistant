/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.controller;

import com.example.smartassistant.common.api.AgentApiResponse;
import com.example.smartassistant.common.api.AgentApiResponses;
import com.example.smartassistant.common.api.AgentError;
import com.example.smartassistant.common.monitoring.AgentErrorMetricsCollector;
import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RouteResponse;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.common.tracing.DistributedTracingService;
import com.example.smartassistant.router.service.core.RouterService;
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
    private final DistributedTracingService tracingService;
    private final AgentErrorMetricsCollector metricsCollector;

    public RouterController(RouterService routerService,
                           DistributedTracingService tracingService,
                           AgentErrorMetricsCollector metricsCollector) {
        this.routerService = routerService;
        this.tracingService = tracingService;
        this.metricsCollector = metricsCollector;
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
                    .fromCache(routingResult.getFromCache() != null && routingResult.getFromCache())
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
    public ResponseEntity<AgentApiResponse<Map<String, Object>>> reactRoute(@Valid @RequestBody RouteRequest request) {
        log.info("[ReactAgent Router API] 收到 ReactAgent 路由请求: userId={}, question={}",
                request.getUserId(), truncate(request.getQuestion()));

        long startTime = System.currentTimeMillis();
        String requestId = request.getRequestId();

        try {
            var context = Map.<String, Object>of("userId", request.getUserId());
            // 路由决策已合并到标准路由流程
            RoutingResult routingResult = routerService.route(request);

            long latency = System.currentTimeMillis() - startTime;

            if (routingResult == null || routingResult.getAgentName() == null) {
                AgentError error = AgentError.builder()
                        .code(AgentApiResponses.ERROR_AGENT_FAILED)
                        .title("路由决策失败")
                        .detail("路由决策失败")
                        .build();
                metricsCollector.recordError(error);
                return ResponseEntity.internalServerError().body(
                        AgentApiResponses.error(error, requestId, latency));
            }

            Map<String, Object> data = new HashMap<>();
            data.put("serviceName", routingResult.getAgentName());
            data.put("confidence", routingResult.getConfidence());
            data.put("note", "这是路由决策结果（含 Agent 回复）");

            log.info("[ReactAgent Router API] 路由完成: serviceName={}, latency={}ms",
                    routingResult.getAgentName(), latency);

            return ResponseEntity.ok(AgentApiResponses.ok(data, routingResult.getAgentName(), requestId, latency));

        } catch (Exception e) {
            log.error("[ReactAgent Router API] 路由失败: {}", e.getMessage(), e);
            AgentError error = AgentError.builder()
                    .code(AgentApiResponses.ERROR_INTERNAL)
                    .title("路由失败")
                    .detail("路由失败: " + e.getMessage())
                    .build();
            metricsCollector.recordError(error);
            return ResponseEntity.internalServerError().body(
                    AgentApiResponses.error(error, requestId, System.currentTimeMillis() - startTime));
        }
    }

    /**
     * 对比测试：传统路由 vs ReactAgent 路由
     */
    @PostMapping("/compare/route")
    public ResponseEntity<AgentApiResponse<Map<String, Object>>> compareRoute(@Valid @RequestBody RouteRequest request) {
        log.info("[Compare Router API] 开始对比测试: userId={}, question={}",
                request.getUserId(), truncate(request.getQuestion()));

        long startTime = System.currentTimeMillis();
        String requestId = request.getRequestId();

        Map<String, Object> data = new HashMap<>();

        // 1. 传统路由
        long traditionalStart = System.currentTimeMillis();
        try {
            RoutingResult traditionalResult = routerService.route(request);
            long traditionalLatency = System.currentTimeMillis() - traditionalStart;

            data.put("traditional_routing", Map.of(
                    "success", true,
                    "result_length", traditionalResult.getResult() != null ? traditionalResult.getResult().length() : 0,
                    "latency_ms", traditionalLatency,
                    "method", "STRATEGY_MANAGER"
            ));
        } catch (Exception e) {
            data.put("traditional_routing", Map.of(
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
                data.put("keyword_routing", Map.of(
                        "success", true,
                        "agent", routingResult.getAgentName(),
                        "result_length", routingResult.getResult() != null ? routingResult.getResult().length() : 0,
                        "latency_ms", reactLatency,
                        "method", "KEYWORD_ROUTING"
                ));
            } else {
                data.put("keyword_routing", Map.of(
                        "success", false,
                        "error", "路由决策为空"
                ));
            }
        } catch (Exception e) {
            data.put("keyword_routing", Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }

        data.put("question", request.getQuestion());
        data.put("userId", request.getUserId());

        long elapsed = System.currentTimeMillis() - startTime;
        return ResponseEntity.ok(AgentApiResponses.ok(data, "router", requestId, elapsed));
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
