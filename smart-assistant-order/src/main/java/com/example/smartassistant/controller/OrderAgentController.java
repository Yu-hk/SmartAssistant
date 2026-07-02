/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.memory.ContextOrchestrator;
import com.example.smartassistant.common.memory.MemoryExtractor;
import com.example.smartassistant.service.core.OrderIntentService;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import com.example.smartassistant.service.core.OrderRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@RequestMapping("/api/order/agent")
public class OrderAgentController {

    private static final Logger log = LoggerFactory.getLogger(OrderAgentController.class);

    private final SmartReActAgent orderAgent;
    private final OrderIntentService intentService;
    private final OrderRagService ragService;
    /** 记忆后台提取器：对话结束后自动提取用户偏好 */
    private final MemoryExtractor memoryExtractor;
    /** 上下文协调器：统一调度四层记忆预算 */
    private final ContextOrchestrator orchestrator;

    public OrderAgentController(SmartReActAgent orderAgent,
                                OrderIntentService intentService,
                                OrderRagService ragService,
                                MemoryExtractor memoryExtractor,
                                ContextOrchestrator orchestrator) {
        this.orderAgent = orderAgent;
        this.intentService = intentService;
        this.ragService = ragService;
        this.memoryExtractor = memoryExtractor;
        this.orchestrator = orchestrator;
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
    public String processQuestion(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return "❌ 问题不能为空";
        }

        long startTime = System.currentTimeMillis();
        log.info("[OrderAgent] 收到请求: question={}", question);

        try {
            // Step 1: 意图识别
            IntentType intent = intentService.detect(question);

            // Step 2: ⭐ 上下文协调器 — 统一调度四层记忆预算
            String userId = request.get("userId");
            List<String> extras = new ArrayList<>();

            // Step 3: RAG 预检索作为额外上下文
            if (ragService != null) {
                String ragContext = ragService.buildEnhancedMessage(intent, question);
                if (!ragContext.equals(question)) {
                    extras.add(ragContext);
                    log.info("[OrderAgent] RAG 预检索已注入上下文");
                }
            }

            // 通过 Orchestrator 构建分层 prompt
            String enhancedQuestion = orchestrator.buildPrompt(question, userId, "order",
                    extras.isEmpty() ? null : extras);
            log.info("[OrderAgent] 状态锚点已强制注入: userId={}", userId != null ? userId : "访客");

            // Step 4: Agent 执行
            log.info("[OrderAgent] 意图识别: {}, userId={}, 记忆注入={}", intent.getLabel(), userId, userId != null);
            String result = orderAgent.execute(enhancedQuestion);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[OrderAgent] 处理完成: intent={},耗时={}ms,结果长度={}",
                    intent.getLabel(), elapsed, result != null ? result.length() : 0);

            // ⭐ Step 5: 后台自动提取偏好（不阻塞响应）
            if (result != null && userId != null && !userId.isBlank() && !"null".equals(userId)) {
                final String finalQuestion = question;
                final String finalResult = result;
                CompletableFuture.runAsync(() ->
                    memoryExtractor.extractFromConversation("order", userId, finalQuestion, finalResult));
            }

            return result != null ? result : "⚠️ Agent 返回空结果";

        } catch (Exception e) {
            log.error("[OrderAgent] 处理失败: {}", e.getMessage(), e);
            return "❌ 处理失败: " + e.getMessage();
        }
    }
}
