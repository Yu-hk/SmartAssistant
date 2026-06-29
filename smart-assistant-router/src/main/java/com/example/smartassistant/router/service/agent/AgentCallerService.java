/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.scheduler.AgentSchedulerService;
import com.example.smartassistant.common.scheduler.AgentTask;
import com.example.smartassistant.common.scheduler.AgentTaskFactory;
import com.example.smartassistant.common.scheduler.AgentTaskStatus;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.RouteDecision;
import com.example.smartassistant.router.service.extraction.KeywordExtractionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Agent Caller Service - 调用 Provider Agent
 * <p>
 * 使用自定义 HTTP 直调替代 A2A 协议。
 * Router 直接 POST 请求到 Agent 服务的 {@code /api/order/agent/process} 端点，
 * 不依赖 Spring AI Alibaba 的 A2aRemoteAgent 框架。
 * </p>
 * <p>
 * <b>版本协商</b>：集成 {@link AgentVersionNegotiator} 选择兼容版本的 Agent。
 * 当版本协商失败时（如元数据缺失），回退到原有按名匹配逻辑。
 * </p>
 */
@Service
public class AgentCallerService {

    private static final Logger log = LoggerFactory.getLogger(AgentCallerService.class);

    /**
     * 默认 Router 版本号，用于版本协商
     */
    private static final String DEFAULT_CLIENT_VERSION = "1.0.0";

    /**
     * 默认协议版本
     */
    private static final String DEFAULT_PROTOCOL_VERSION = "a2a-v1";

    private final AgentDiscoveryService agentDiscoveryService;
    private final AgentVersionNegotiator versionNegotiator;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // ⭐ P4 调度服务（可选：降级走同步 HTTP）
    private AgentSchedulerService schedulerService;

    public AgentCallerService(AgentDiscoveryService agentDiscoveryService,
                             KeywordExtractionService keywordExtractionService,
                             AgentVersionNegotiator versionNegotiator) {
        this.agentDiscoveryService = agentDiscoveryService;
        this.versionNegotiator = versionNegotiator;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 注入调度服务（支持延迟注入，允许先初始化 AgentCallerService 再启动调度器）
     */
    public void setSchedulerService(AgentSchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    // ==================== 公开方法 ====================

    /**
     * ⭐ 获取可用 Agent 数量。
     * 当无任何 Agent 注册时，Router 直接使用内联 Ollama 兜底。
     */
    public int getAvailableAgentCount() {
        try {
            List<DiscoveredAgent> agents = agentDiscoveryService.discoverAllAgents();
            return agents != null ? agents.size() : 0;
        } catch (Exception e) {
            log.warn("[AgentCaller] 获取 Agent 列表失败: {}", e.getMessage());
            return 0;
        }
    }

    public String callAgent(String agentName, String question, Long userId) {
        return callAgentWithContext(agentName, question, userId, null, null);
    }

    public String callAgent(String agentName, String question, Long userId, String requestId) {
        return callAgentWithContext(agentName, question, userId, null, requestId);
    }

    public AgentCallResult callAgentAndExtractTitles(String agentName, String question, Long userId) {
        return callAgentAndExtractTitles(agentName, question, userId, null);
    }

    public AgentCallResult callAgentAndExtractTitles(String agentName, String question, Long userId, String requestId) {
        log.info("[AgentCaller] callAgentAndExtractTitles: agent={}, userId={}, questionLength={}, requestId={}",
                agentName, userId, question != null ? question.length() : 0, requestId);

        try {
            String result = callAgentWithContext(agentName, question, userId, null, requestId);
            return new AgentCallResult(result);
        } catch (Exception e) {
            log.error("[AgentCaller] Agent 调用失败: {}, 错误: {}", agentName, e.getMessage(), e);
            return new AgentCallResult("❌ 调用 Agent 失败: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════
    // ⭐ P4 异步 Agent 调用（任务队列模式）
    // ═════════════════════════════════════════════════════

    /**
     * 异步调用 Agent（通过任务队列调度）。
     * <p>
     * 任务入队后立即返回 taskId，调用方通过 {@code taskId} 轮询结果。
     * 降级策略：调度服务不可用时，退化为同步 HTTP 调用。
     * </p>
     *
     * @param agentName 目标 Agent 名称
     * @param question  Agent 处理的问题
     * @param userId    用户 ID
     * @param requestId 路由请求 ID
     * @param intentTag 意图标签
     * @param confidence 置信度
     * @return taskId（异步模式）或 null（降级为同步）
     */
    public String callAgentAsync(String agentName, String question, String originalQuestion,
                                  Long userId, String requestId, String intentTag, double confidence) {
        if (schedulerService == null) {
            log.warn("[AgentCaller] 调度服务不可用，降级为同步调用: agent={}", agentName);
            return null; // 调用方应降级到同步模式
        }

        AgentTask task = AgentTaskFactory.createTaskWithContext(
                agentName, question, originalQuestion, userId, requestId, null, intentTag, confidence);
        task.setPriority(15); // 异步任务默认较高优先级

        return schedulerService.submitAsync(task);
    }

    /**
     * 轮询异步任务结果。
     *
     * @param taskId 任务 ID
     * @return 任务（含结果），可能为 empty 表示尚未完成
     */
    public java.util.Optional<AgentTask> pollAsyncResult(String taskId) {
        if (schedulerService == null || taskId == null) {
            return java.util.Optional.empty();
        }
        return schedulerService.pollResult(taskId);
    }

    /**
     * 判断异步任务是否已完成。
     */
    public boolean isAsyncTaskDone(String taskId) {
        return pollAsyncResult(taskId)
                .map(t -> t.isTerminal())
                .orElse(false);
    }

    public String callAgentWithContext(String agentName, String question, Long userId,
                                       RouteDecision.ExtractedContext context) {
        return callAgentWithContext(agentName, question, userId, context, null);
    }

    /**
     * HTTP 直调 Agent — 替代 A2A 协议。
     * <p>
     * 从 Nacos 发现 Agent 地址后，直接 POST 到 {@code /api/order/agent/process}。
     * 请求体为 {question: "用户问题"}，响应为纯文本答案。
     * </p>
     */
    public String callAgentWithContext(String agentName, String question, Long userId,
                                       RouteDecision.ExtractedContext context, String requestId) {
        log.info("[AgentCaller] HTTP 直调 Agent: {}, userId={}, questionLength={}, requestId={}",
                agentName, userId, question != null ? question.length() : 0, requestId);

        if (context != null) {
            log.info("[AgentCaller] 提取的上下文: location={}, intent={}",
                    context.getLocation(), context.getIntent());
        }

        try {
            // ⭐ 特殊 Agent 名称：builtin_fallback 和 none 是内部兜底标记，不实际调用
            if ("builtin_fallback".equals(agentName) || "none".equals(agentName)) {
                log.warn("[AgentCaller] 特殊 Agent 名称 '{}'，跳过 HTTP 调用", agentName);
                return "";
            }

            String agentUrl = findAgentUrl(agentName);
            if (agentUrl == null) {
                log.error("[AgentCaller] 未找到 Agent: {}", agentName);
                return "❌ 未找到目标 Agent: " + agentName;
            }

            // 从 Nacos 返回的 /a2a 路径转换为自定义 HTTP 端点
            String baseUrl = agentUrl.replaceAll("/a2a$", "");
            String processUrl = baseUrl + "/api/order/agent/process";

            log.info("[AgentCaller] HTTP 直调 URL: {}", processUrl);

            // 构建请求体 {question: "...", userId: "..."}
            Map<String, String> requestBody = new java.util.LinkedHashMap<>();
            requestBody.put("question", question);
            if (userId != null) {
                requestBody.put("userId", String.valueOf(userId));
            }
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (requestId != null && !requestId.isBlank()) {
                headers.set("X-Request-Id", requestId);
            }

            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

            // 直接 HTTP POST 调用
            ResponseEntity<String> response = restTemplate.postForEntity(processUrl, entity, String.class);

            String result = response.getBody();
            if (result == null || result.isBlank()) {
                log.warn("[AgentCaller] Agent 返回空结果: {}", agentName);
                return "⚠️ Agent 返回空结果";
            }

            result = cleanThinkingContent(result);

            log.info("[AgentCaller] HTTP 直调成功: agent={}, status={}, resultLength={}",
                    agentName, response.getStatusCode(), result.length());
            return result;

        } catch (Exception e) {
            log.error("[AgentCaller] HTTP 直调失败: {}, 错误: {}", agentName, e.getMessage(), e);
            return "❌ 调用 Agent 失败: " + e.getMessage();
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 从 AgentDiscoveryService 查找目标 Agent 的 URL。
     * <p>
     * <b>改进</b>：优先使用 {@link AgentVersionNegotiator} 进行版本协商，
     * 选择兼容版本的 Agent 实例。如果版本协商失败（如元数据缺失），
     * 则回退到原有按名匹配逻辑。
     * </p>
     */
    private String findAgentUrl(String agentName) {
        try {
            // ⭐ 第一步：尝试版本协商（选择兼容版本）
            DiscoveredAgent negotiated = versionNegotiator.selectCompatibleAgent(
                    agentName, DEFAULT_CLIENT_VERSION, DEFAULT_PROTOCOL_VERSION);
            if (negotiated != null && negotiated.getUrl() != null) {
                log.info("[AgentCaller] ✅ 版本协商成功: agent={}, url={}, version={}",
                        agentName, negotiated.getUrl(),
                        negotiated.getMetadata() != null ? negotiated.getMetadata().getVersion() : "unknown");
                return negotiated.getUrl();
            }

            // ⭐ 第二步：版本协商失败（无匹配版本），回退到按名匹配
            log.warn("[AgentCaller] 版本协商无兼容 Agent: {}, 回退到直接匹配", agentName);
            List<DiscoveredAgent> agents = agentDiscoveryService.discoverAllAgents();
            for (DiscoveredAgent agent : agents) {
                if (agent.getAgentName().equals(agentName) ||
                    agent.getServiceName().equals(agentName)) {
                    log.info("[AgentCaller] 找到 Agent (回退): {}, URL: {}", agentName, agent.getUrl());
                    return agent.getUrl();
                }
            }
            log.warn("[AgentCaller] 未找到 Agent: {}, 可用 Agents: {}",
                    agentName, agents.stream().map(DiscoveredAgent::getAgentName).toList());
            return null;
        } catch (Exception e) {
            log.error("[AgentCaller] 查找 Agent URL 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 清理回复中的思考过程内容。
     * DeepSeek R1 等推理模型会在回复开头输出推理步骤，对用户是噪音。
     */
    private String cleanThinkingContent(String response) {
        if (response == null || response.isEmpty()) return response;

        String original = response;

        // 策略1：移除 [ModelThinking]...[/ModelThinking]
        if (response.contains("[ModelThinking]")) {
            response = response.replaceAll("(?s)\\[ModelThinking].*?\\[/ModelThinking]", "");
        }
        // 策略2：移除 [思考内容] 区块
        if (response.contains("[思考内容]")) {
            response = response.replaceAll("(?s)\\[思考内容].*?\\[/思考内容]", "");
        }
        // 策略3：移除 [思考]...[/思考]
        if (response.contains("[思考]")) {
            response = response.replaceAll("(?s)\\[思考].*?\\[/思考]", "");
        }
        // 策略4：移除 [reasoning]...[/reasoning]
        if (response.contains("[reasoning]")) {
            response = response.replaceAll("(?s)\\[reasoning].*?\\[/reasoning]", "");
        }

        if (response.length() < original.length()) {
            log.info("[AgentCaller] 清理思考过程，长度: {} -> {}", original.length(), response.length());
        }

        return response;
    }
}
